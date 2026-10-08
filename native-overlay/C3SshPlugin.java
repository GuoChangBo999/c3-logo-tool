package com.openpilot.c3logo;

import android.util.Base64;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.OutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.EnumSet;
import java.util.concurrent.TimeUnit;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.common.IOUtils;
import net.schmizz.sshj.sftp.OpenMode;
import net.schmizz.sshj.sftp.RemoteFile;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import net.schmizz.sshj.userauth.keyprovider.KeyProvider;
import net.schmizz.sshj.userauth.password.PasswordUtils;
import net.schmizz.sshj.connection.channel.direct.Session;

/**
 * C3 AGNOS 原生 SSH/SFTP 插件
 * 精准匹配 SSHJ 0.38 API
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

                // 1) 私钥认证：写临时文件，让 SSHJ 自动识别 OpenSSH / PPK
                if (privateKeyContent != null && !privateKeyContent.isEmpty()) {
                    File tmp = null;
                    try {
                        tmp = File.createTempFile("c3key", ".tmp", getContext().getCacheDir());
                        try (FileOutputStream fos = new FileOutputStream(tmp)) {
                            fos.write(privateKeyContent.getBytes("UTF-8"));
                        }
                        KeyProvider kp;
                        if (passphrase != null && !passphrase.isEmpty()) {
                            kp = client.loadKeys(tmp.getAbsolutePath(), passphrase.toCharArray());
                        } else {
                            kp = client.loadKeys(tmp.getAbsolutePath());
                        }
                        client.authPublickey(username, kp);
                        authed = true;
                    } catch (Exception keyErr) {
                        // 密钥失败，尝试回退密码
                        if (password == null || password.isEmpty()) {
                            throw keyErr;
                        }
                    } finally {
                        if (tmp != null) tmp.delete();
                    }
                }

                // 2) 密码认证
                if (!authed && password != null && !password.isEmpty()) {
                    client.authPassword(username, password);
                    authed = true;
                }

                if (!authed) {
                    client.close();
                    call.reject("认证失败：请检查密码或密钥");
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
                // 先写本地临时文件，再上传（兼容 SSHJ 0.38 的 put API）
                File tmp = File.createTempFile("c3up", ".tmp", getContext().getCacheDir());
                try (FileOutputStream fos = new FileOutputStream(tmp)) {
                    fos.write(data);
                }
                sftp = ssh.newSFTPClient();
                sftp.put(tmp.getAbsolutePath(), remotePath);
                tmp.delete();

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