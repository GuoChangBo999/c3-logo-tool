/**
 * JS <-> 原生 Android SSH 插件 桥接层
 * 依赖 Capacitor 的 registerPlugin
 */
import { registerPlugin } from '@capacitor/core';

const C3Ssh = registerPlugin('C3Ssh');

/** 建立 SSH 连接
 * @param {object} opts
 * @param {string} opts.host
 * @param {string|number} opts.port
 * @param {string} opts.username
 * @param {string} [opts.password]      密码认证
 * @param {string} [opts.privateKey]    私钥文件内容（OpenSSH 或 PuTTY .ppk）
 * @param {string} [opts.passphrase]    私钥口令（加密私钥才需要）
 */
export async function connectSsh({ host, port, username, password, privateKey, passphrase }) {
  const payload = {
    host: (host || '').trim(),
    port: parseInt(port || '22', 10),
    username: (username || 'comma').trim(),
    password: password || '',
  };
  if (privateKey && String(privateKey).trim()) payload.privateKey = String(privateKey);
  if (passphrase && String(passphrase).length) payload.passphrase = String(passphrase);
  return C3Ssh.connect(payload);
}

/** 断开连接 */
export async function disconnect() {
  return C3Ssh.disconnect();
}

/** 执行远程命令，返回输出 */
export async function execSshCommand(command) {
  const res = await C3Ssh.exec({ command });
  if (res && res.output) return res.output;
  return '';
}

/** 通过 SFTP 上传字节到远程路径 */
export async function uploadBytes(bytes, remotePath) {
  // Uint8Array -> base64（分块避免爆栈）
  const b64 = bytesToBase64(bytes);
  return C3Ssh.upload({ remotePath, data: b64 });
}

function bytesToBase64(bytes) {
  const CHUNK = 0x8000;
  let binary = '';
  for (let i = 0; i < bytes.length; i += CHUNK) {
    const sub = bytes.subarray(i, i + CHUNK);
    binary += String.fromCharCode.apply(null, sub);
  }
  return btoa(binary);
}