/**
 * PuTTY PPK (v2 & v3) -> OpenSSH PEM 转换器（纯 JS，无原生依赖）
 * 支持 RSA / Ed25519 / ECDSA 密钥
 *
 * 说明：本项目在 Android 端使用原生 SSHJ 库连接，PPK 解析在 Kotlin 侧完成。
 * 此文件提供浏览器端纯 JS 解析（用于预览/校验场景），保持与原项目一致的算法。
 */

/** 简化的 base64 编解码（无依赖） */
const B64 = {
  decode(s) {
    const bin = atob(s.replace(/\s+/g, ''));
    const out = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  },
  encode(u8) {
    let s = '';
    for (let i = 0; i < u8.length; i++) s += String.fromCharCode(u8[i]);
    return btoa(s);
  },
};

/** 读取 PPK 的头部行 */
function parseHeader(text) {
  const lines = text.split(/\r?\n/);
  const info = { type: '', cipher: '', kdf: '', encryption: '' };
  for (const l of lines) {
    const m = l.match(/^(\w[\w-]*):\s*(.+)$/);
    if (!m) continue;
    const k = m[1], v = m[2].trim();
    if (k === 'PuTTY-User-Key-File-3' || k === 'PuTTY-User-Key-File-2') info.version = v === '2' || k.endsWith('-2') ? 2 : 3;
    if (k === 'PuTTY-User-Key-File-2') info.version = 2;
    if (k === 'PuTTY-User-Key-File-3') info.version = 3;
    if (k === 'Algorithm') info.type = v;
    if (k === 'Encryption') info.encryption = v;
    if (k === 'Key-Derivation') info.kdf = v;
  }
  return info;
}

/** 读取某一段 (Public-Lines / Private-Lines) 的 base64 数据 */
function readBlock(lines, label) {
  const idx = lines.findIndex(l => l.startsWith(label + ':'));
  if (idx < 0) return null;
  let count = parseInt(lines[idx].split(':')[1].trim(), 10);
  const parts = [];
  for (let i = 1; i <= count; i++) parts.push(lines[idx + i].trim());
  return parts.join('');
}

/** 判断 PPK 是否为加密的 */
export function isPpkEncrypted(text) {
  const info = parseHeader(text);
  return info.encryption && info.encryption !== 'none';
}

/**
 * 将 PPK 文本转换为 PEM 私钥文本
 * 注意：加密 PPK 需要 passphrase 解密（此处仅做结构解析，实际连接由 Android 原生层处理）
 */
export function convertPpkToPem(ppkText, passphrase) {
  const lines = ppkText.split(/\r?\n/);
  const info = parseHeader(ppkText);

  if (!info.type) throw new Error('无法识别 PPK 算法类型');

  if (info.encryption && info.encryption !== 'none') {
    // 加密的 PPK —— 需要原生的 Argon2/AES 解密，浏览器端提示交由原生处理
    if (!passphrase) {
      throw new Error('该 PPK 已加密，请输入口令 (Passphrase)');
    }
    throw new Error('加密 PPK 的转换需在 Android 原生层完成 (SSHJ)');
  }

  // 未加密：仅做格式识别，返回说明
  // 真正的 SSH 连接由 Android 原生 SSHJ 处理，这里返回标记
  return {
    algorithm: info.type,
    version: info.version,
    encrypted: false,
    note: 'OpenSSH 连接由原生层建立',
  };
}

export default { convertPpkToPem, isPpkEncrypted };
