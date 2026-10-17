package xyz.yuluoqc.c3mirror;

import android.content.Context;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.DefaultConfig;
import net.schmizz.sshj.common.Factory;
import net.schmizz.sshj.common.SecurityUtils;
import net.schmizz.sshj.transport.kex.DHG14;
import net.schmizz.sshj.transport.kex.DHGexSHA256;
import net.schmizz.sshj.transport.kex.ECDHNistP;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import net.schmizz.sshj.common.IOUtils;
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder;
import net.schmizz.sshj.connection.channel.direct.Parameters;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import net.schmizz.sshj.userauth.keyprovider.KeyProvider;
import net.schmizz.sshj.userauth.keyprovider.OpenSSHKeyFile;
import net.schmizz.sshj.xfer.FileSystemFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

/**
 * Handles all SSH work against the comma device:
 *   1) upload the bundled c3-screen-v2.tar and run install.sh (deploy)
 *   2) open local TCP forwards: localhost:lport -> car:rport (tunnel)
 *
 * Auth supports BOTH methods:
 *   - SSH private key (OpenSSH / PKCS#8, optional passphrase)  [preferred]
 *   - password (fallback, e.g. the default comma/comma)
 *
 * If a private key is supplied, we try key auth first and, when it fails,
 * fall back to password (unless the password is empty).
 */
public class SshDeployer {

    public interface Log {
        void log(String line);
    }

    private final Context ctx;
    private final String host;
    private final String user;
    private final String pass;
    private final String privateKey;   // PEM/OpenSSH content, may be null/empty
    private final String passphrase;   // key passphrase, may be null/empty
    private final Log log;

    private SSHClient ssh;
    private final java.util.List<PortForward> forwards = new java.util.ArrayList<>();

    public SshDeployer(Context ctx, String host, String user, String pass,
                       String privateKey, String passphrase, Log log) {
        this.ctx = ctx;
        this.host = host;
        this.user = user;
        this.pass = pass;
        this.privateKey = privateKey;
        this.passphrase = passphrase;
        this.log = log;
    }

    /** Convenience ctor: password-only (kept for backward compatibility). */
    public SshDeployer(Context ctx, String host, String user, String pass, Log log) {
        this(ctx, host, user, pass, null, null, log);
    }

    private void say(String s) {
        if (log != null) log.log(s);
    }


    /**
     * Make sure every crypto primitive sshj may ask for actually resolves.
     *
     * Two layers, deliberately redundant:
     *   A) Register the BouncyCastle provider so the X25519 / Ed25519 SPIs
     *      shipped in bcprov-jdk18on are reachable via "BC". Without this,
     *      modern OpenSSH keys and curve25519-sha256 KEX blow up with
     *      "no such algorithm: X25519 for provider BC".
     *   B) Build a DefaultConfig whose KEX list avoids curve25519 entirely and
     *      instead advertises the JDK-native (SunEC / SunJCE) groups:
     *      ecdh-sha2-nistp256 / nistp384 / nistp521 + DH group14/group-exchange.
     *      Those never touch the BC provider, so they work even if BC is
     *      absent or broken on some ROM.
     *
     * Called once, before the very first SSHClient is constructed.
     */
    private static volatile boolean CRYPTO_READY = false;

    /** Best-effort logger usable from static context (crypto bootstrap). */
    private static void bootLog(String msg) {
        System.err.println("[C3Mirror] " + msg);
    }

    private static synchronized void ensureCryptoProviders() {
        if (CRYPTO_READY) return;
        try {
            if (java.security.Security.getProvider("BC") == null) {
                java.security.Security.addProvider(new BouncyCastleProvider());
            }
            // sshj caches its provider choice in a static field - point it at BC
            // explicitly so lookups like KeyAgreement.getInstance("X25519","BC")
            // succeed instead of falling through to the platform provider.
            if (!SecurityUtils.isBouncyCastleRegistered()) {
                if (!SecurityUtils.registerSecurityProvider("BC")) {
                    SecurityUtils.setSecurityProvider("BC");
                }
            }
        } catch (Throwable t) {
            // Non fatal: layer (B) below keeps us alive without BC.
            bootLog("BC provider 注册警告: " + t);
        }
        CRYPTO_READY = true;
    }

    /**
     * A config that prefers JDK-native key exchange, so a broken/incomplete
     * BouncyCastle can never stop us from negotiating a session.
     */
    private static DefaultConfig safeConfig() {
        DefaultConfig cfg = new DefaultConfig();
        try {
            // NOTE: build the list explicitly - a generic ARRAY
            // (new Factory.Named<KeyExchange>[]{...}) is a javac error
            // ("generic array creation"). Verified against sshj 0.38.0.
            java.util.List<Factory.Named<net.schmizz.sshj.transport.kex.KeyExchange>> kex =
                    new java.util.ArrayList<Factory.Named<net.schmizz.sshj.transport.kex.KeyExchange>>();
            kex.add(new ECDHNistP.Factory521());
            kex.add(new ECDHNistP.Factory384());
            kex.add(new ECDHNistP.Factory256());
            kex.add(new DHGexSHA256.Factory());
            kex.add(new DHG14.Factory());
            cfg.setKeyExchangeFactories(kex);
        } catch (Throwable t) {
            // If for any reason we cannot override, fall back to the defaults.
            bootLog("KEX 配置回退到默认: " + t);
        }
        return cfg;
    }

    private static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }

    public void connect() throws Exception {
        ensureCryptoProviders();
        SSHClient c = new SSHClient(safeConfig());
        c.addHostKeyVerifier(new PromiscuousVerifier());
        c.setConnectTimeout(8000);
        c.setTimeout(10000);
        c.connect(host, 22);

        boolean authed = false;

        // 1) Try public-key auth first when a key is provided.
        if (!blank(privateKey)) {
            try {
                KeyProvider kp = loadKeyProvider(c);
                c.authPublickey(user, kp);
                say("SSH 已连接（密钥认证） " + user + "@" + host);
                authed = true;
            } catch (Exception e) {
                say("密钥认证失败: " + e.getMessage() + "，尝试密码...");
            }
        }

        // 2) Fall back to password.
        if (!authed) {
            if (blank(pass)) {
                throw new Exception("密钥与密码均不可用，无法认证");
            }
            c.authPassword(user, pass);
            say("SSH 已连接（密码认证） " + user + "@" + host);
        }

        ssh = c;
    }

    /**
     * Build a KeyProvider from the raw private-key text. Handles OpenSSH keys
     * as well as PKCS#8 (BEGIN PRIVATE KEY / BEGIN RSA PRIVATE KEY) via sshj's
     * loadKeys fallback.
     */
    private KeyProvider loadKeyProvider(SSHClient c) throws Exception {
        String pem = privateKey.trim();
        // sshj's loadKeys(String) expects a path, so write the key to a temp file
        // and let sshj sniff the format; this is the most compatible route.
        File tmp = new File(ctx.getCacheDir(), "c3_id_key");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            fos.write(pem.getBytes("UTF-8"));
        }
        try {
            if (!blank(passphrase)) {
                return c.loadKeys(tmp.getAbsolutePath(), passphrase.toCharArray());
            }
            return c.loadKeys(tmp.getAbsolutePath());
        } catch (Exception e) {
            // Last resort: explicitly parse as OpenSSH key from content.
            OpenSSHKeyFile okf = new OpenSSHKeyFile();
            okf.init(pem, null);
            return okf;
        } finally {
            // best-effort scrub
            try { tmp.delete(); } catch (Exception ignored) {}
        }
    }

    public boolean isConnected() {
        return ssh != null && ssh.isConnected();
    }

    /** Run a command and return combined output. */
    public String exec(String cmd) throws Exception {
        net.schmizz.sshj.connection.channel.direct.Session sess = ssh.startSession();
        try {
            net.schmizz.sshj.connection.channel.direct.Session.Command sc = sess.exec(cmd);
            InputStream in = sc.getInputStream();
            InputStream err = sc.getErrorStream();
            String out = IOUtils.readFully(in).toString();
            String e = IOUtils.readFully(err).toString();
            sc.join(30, java.util.concurrent.TimeUnit.SECONDS);
            return out + (e.isEmpty() ? "" : "\n[stderr] " + e);
        } finally {
            try { sess.close(); } catch (Exception ignored) {}
        }
    }

    /** Deploy the bundled c3-screen-v2 package and start the stream. */
    public void deploy() throws Exception {
        say("开始部署车机端...");
        // 1) make sure /data/local/tmp is writable
        exec("sudo -n mkdir -p /data/local/tmp && sudo -n chmod 777 /data/local/tmp || true");

        // 2) write bundled tar to a temp file and scp it up
        File tmp = new File(ctx.getCacheDir(), "c3-screen-v2.tar");
        try (InputStream is = ctx.getAssets().open("c3-screen-v2.tar");
             FileOutputStream fos = new FileOutputStream(tmp)) {
            byte[] b = new byte[8192];
            int n;
            while ((n = is.read(b)) > 0) fos.write(b, 0, n);
        }
        say("上传 c3-screen-v2.tar (" + tmp.length() + " bytes)...");
        ssh.newSCPFileTransfer().upload(new FileSystemFile(tmp.getAbsolutePath()),
                "/data/local/tmp/c3-screen-v2.tar");
        say("上传完成");

        // 3) unpack + run install.sh  (mirror of the original app flow)
        String cmd = "rm -rf /data/local/tmp/c3-screen-v2-package && "
                + "mkdir -p /data/local/tmp/c3-screen-v2-package && "
                + "tar -xf /data/local/tmp/c3-screen-v2.tar -C /data/local/tmp/c3-screen-v2-package && "
                + "cd /data/local/tmp/c3-screen-v2-package && sh ./install.sh; "
                + "echo '---STATUS---'; "
                + "test -x /data/local/tmp/c3-screen-stream-v2/bin/v4l_h264_encoder && echo ENCODER_OK || echo ENCODER_MISSING";
        String out = exec(cmd);
        say(out);
        if (out.contains("ENCODER_OK")) {
            say("车机端部署成功 ✅");
        } else {
            say("⚠️ 未检测到编码器，请检查车机 root/目录");
        }
    }

    /**
     * Start a local forward: local 127.0.0.1:localPort -> car:remotePort.
     * Returns immediately; the forward runs in a daemon thread.
     */
    public void addLocalForward(int localPort, String remoteHost, int remotePort) throws Exception {
        Parameters params = new Parameters("127.0.0.1", localPort, remoteHost, remotePort);
        ServerSocket serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress("127.0.0.1", localPort));
        LocalPortForwarder forwarder = ssh.newLocalPortForwarder(params, serverSocket);
        PortForward pf = new PortForward(forwarder, serverSocket, localPort, remotePort);
        forwards.add(pf);
        Thread t = new Thread(() -> {
            try {
                say("本地转发 127.0.0.1:" + localPort + " -> " + remoteHost + ":" + remotePort);
                forwarder.listen();
            } catch (Exception ignored) {
                // forwarder stopped
            }
        }, "fwd-" + localPort);
        t.setDaemon(true);
        t.start();
    }

    public void close() {
        for (PortForward pf : forwards) {
            try { pf.forwarder.close(); } catch (Exception ignored) {}
            try { pf.serverSocket.close(); } catch (Exception ignored) {}
        }
        forwards.clear();
        try { if (ssh != null) ssh.disconnect(); } catch (Exception ignored) {}
    }

    private static class PortForward {
        final LocalPortForwarder forwarder;
        final ServerSocket serverSocket;
        final int localPort;
        final int remotePort;

        PortForward(LocalPortForwarder f, ServerSocket s, int lp, int rp) {
            forwarder = f; serverSocket = s; localPort = lp; remotePort = rp;
        }
    }
}