package com.openpilot.c3logo;

import android.util.Base64;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.KeyPair;
import java.util.concurrent.TimeUnit;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.common.IOUtils;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import net.schmizz.sshj.userauth.keyprovider.OpenSSHKeyFile;
import net.schmizz.sshj.userauth.keyprovider.PuTTYKeyFile;
import net.schmizz.sshj.userauth.password.PasswordUtils;
import net.schmizz.sshj.connection.channel.direct.Session;

/**
 * C3 AGNOS 原生 SSH/SFTP 插件
 */
@CapacitorPlugin(name = "C3Ssh")
public class C3SshPlugin extends Plugin {

    private SSHClient ssh = null;

    @PluginMethod
    public void connect(PluginCall call) {
        final String host = call.getString("host", "192.168.0.34").trim();
        final int port = call.getInt("port", 22);
        final String username = call.getString("username", "comma").trim();
        final String password = call.getString("password", "");
        final String privateKeyContent = call.getString("privateKey", null);
        final String passphrase = call.getString("passphrase", null);

        new Thread(() -> {
            try {
                SSHClient client = new SSHClient();
                client.addHostKeyVerifier(new PromiscuousVerifier());
                client.setConnectTimeout(8000);
                client.connect(host, port);

                boolean authed = false;
                if (privateKeyContent != null && !privateKeyContent.isEmpty()) {
                    char[] pass = (passphrase != null && !passphrase.isEmpty())
                            ? passphrase.toCharArray() : null;
                    try {
                        KeyPair kp = parseKey(privateKeyContent, pass);
                        client.authPublickey(username, kp);
                        authed = true;
                    } catch (Exception keyErr) {
                        if (password != null && !password.isEmpty()) {
                            client.authPassword(username, password);
                            authed = true;
                        } else {
                            throw keyErr;
                        }
                    }
                } else if (password != null && !password.isEmpty()) {
                    client.authPassword(username, password);
                    authed = true;
                }

                if (!authed) {
                    client.close();
                    call.reject("未提供有效的认证方式（密码或密钥）");
                    return;
                }

                this.ssh = client;
                JSObject ret = new JSObject();
                ret.put("success", true);
                ret.put("message", "已连接 " + host + ":" + port);
                call.resolve(ret);
            } catch (Exception e) {
                call.reject("SSH 连接失败: " + e.getMessage());
            }
        }).start();
    }

    private KeyPair parseKey(String content, char[] passphrase) throws Exception {
        if (content.contains("PuTTY-User-Key-File")) {
            PuTTYKeyFile ppk = new PuTTYKeyFile();
            InputStream is = new ByteArrayInputStream(content.getBytes("UTF-8"));
            ppk.init(is, PasswordUtils.createOneOff(passphrase));
            return ppk.getKeyPair();
        } else {
            OpenSSHKeyFile openssh = new OpenSSHKeyFile();
            InputStream is = new ByteArrayInputStream(content.getBytes("UTF-8"));
            openssh.init(is, PasswordUtils.createOneOff(passphrase));
            return openssh.getKeyPair();
        }
    }

    @PluginMethod
    public void exec(PluginCall call) {
        final String command = call.getString("command", "");
        if (ssh == null || !ssh.isConnected()) {
            call.reject("SSH 尚未连接");
            return;
        }
        new Thread(() -> {
            try (Session session = ssh.startSession()) {
                Session.Command cmd = session.exec(command);
                String output = IOUtils.readFully(cmd.getInputStream()).toString();
                String err = IOUtils.readFully(cmd.getErrorStream()).toString();
                cmd.join(60, TimeUnit.SECONDS);

                JSObject ret = new JSObject();
                ret.put("output", output);
                if (err != null && !err.isEmpty()) ret.put("stderr", err);
                ret.put("exitStatus", cmd.getExitStatus());
                call.resolve(ret);
            } catch (Exception e) {
                call.reject("命令执行失败: " + e.getMessage());
            }
        }).start();
    }

    @PluginMethod
    public void upload(PluginCall call) {
        final String remotePath = call.getString("remotePath", "");
        final String dataB64 = call.getString("data", "");
        if (ssh == null || !ssh.isConnected()) {
            call.reject("SSH 尚未连接");
            return;
        }
        new Thread(() -> {
            SFTPClient sftp = null;
            try {
                byte[] data = Base64.decode(dataB64, Base64.DEFAULT);
                sftp = ssh.newSFTPClient();
                try (OutputStream os = sftp.open(remotePath).new OutputStream()) {
                    os.write(data);
                    os.flush();
                }
                JSObject ret = new JSObject();
                ret.put("success", true);
                ret.put("bytes", data.length);
                call.resolve(ret);
            } catch (Exception e) {
                call.reject("SFTP 上传失败: " + e.getMessage());
            } finally {
                if (sftp != null) {
                    try { sftp.close(); } catch (Exception ignored) {}
                }
            }
        }).start();
    }

    @PluginMethod
    public void disconnect(PluginCall call) {
        try {
            if (ssh != null && ssh.isConnected()) ssh.disconnect();
        } catch (Exception ignored) {}
        ssh = null;
        JSObject ret = new JSObject();
        ret.put("success", true);
        call.resolve(ret);
    }
}