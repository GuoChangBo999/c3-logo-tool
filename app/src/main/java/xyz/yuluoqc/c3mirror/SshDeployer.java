package xyz.yuluoqc.c3mirror;

import android.content.Context;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.common.IOUtils;
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder;
import net.schmizz.sshj.connection.channel.direct.Parameters;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
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
 * The original app used the same sshj library and the same deployment steps
 * (see eb/i3.smali): mkdir /data/local/tmp, upload tar, untar, sh install.sh.
 */
public class SshDeployer {

    public interface Log {
        void log(String line);
    }

    private final Context ctx;
    private final String host;
    private final String user;
    private final String pass;
    private final Log log;

    private SSHClient ssh;
    private final java.util.List<PortForward> forwards = new java.util.ArrayList<>();

    public SshDeployer(Context ctx, String host, String user, String pass, Log log) {
        this.ctx = ctx;
        this.host = host;
        this.user = user;
        this.pass = pass;
        this.log = log;
    }

    private void say(String s) {
        if (log != null) log.log(s);
    }

    public void connect() throws Exception {
        SSHClient c = new SSHClient();
        c.addHostKeyVerifier(new PromiscuousVerifier());
        c.setConnectTimeout(8000);
        c.setTimeout(10000);
        c.connect(host, 22);
        c.authPassword(user, pass);
        ssh = c;
        say("SSH 已连接 " + user + "@" + host);
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
        ssh.newSCPFileTransfer().upload(new FileSystemFile(tmp.getAbsolutePath()), "/data/local/tmp/c3-screen-v2.tar");
        say("上传完成");

        // 3) unpack + run install.sh  (mirror of the original app flow)
        String cmd = "rm -rf /data/local/tmp/c3-screen-v2-package && "
                + "mkdir -p /data/local/tmp/c3-screen-v2-package && "
                + "(tar -xf /data/local/tmp/c3-screen-v2.tar -C /data/local/tmp/c3-screen-v2-package 2>/dev/null "
                + "|| tar -xf /data/local/tmp/c3-screen-v2.tar -C /data/local/tmp/c3-screen-v2-package) && "
                + "sh /data/local/tmp/c3-screen-v2-package/install.sh; "
                + "echo '---STATUS---'; "
                + "test -x /data/local/tmp/c3-screen-stream-v2/bin/v4l_h264_encoder && echo ENCODER_OK || echo ENCODER_MISSING";
        String out = exec(cmd);
        say(out);
        if (out.contains("ENCODER_OK")) {
            say("车机端部署成功 ✅");
        } else {
            say("⚠️ 未检测到编码器，请检查车机 root/顺序");
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
            } catch (Exception e) {
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