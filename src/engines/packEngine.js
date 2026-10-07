/**
 * 恢复包引擎 —— 纯 JS 版（用 fflate 替代 archiver）
 * 生成 agnos_c3_logo_restore_pack.tar.gz
 */
import { gzipSync } from 'fflate';

/** 默认远程安装脚本（与原项目一致） */
export const DEFAULT_INSTALL_SCRIPT = `#!/bin/sh
# C3 AGNOS 启动图恢复安装脚本
set -e
TOOL_DIR=$(cd "$(dirname "$0")" && pwd)

echo "[1/3] 备份当前启动图..."
mkdir -p /data/agnos_logo_tool/backup
sudo dd if=/dev/disk/by-partlabel/splash of=/data/agnos_logo_tool/backup/splash_backup.img bs=1M 2>/dev/null || true
sudo cp /usr/comma/bg.jpg /data/agnos_logo_tool/backup/bg_backup.jpg 2>/dev/null || true

if [ -f "$TOOL_DIR/splash_custom.img" ]; then
  echo "[2/3] 刷入第一屏启动图 (splash 分区)..."
  sudo dd if="$TOOL_DIR/splash_custom.img" of=/dev/disk/by-partlabel/splash bs=1M
fi

if [ -f "$TOOL_DIR/bg.jpg" ]; then
  echo "[2/3] 部署第二屏背景图..."
  sudo cp "$TOOL_DIR/bg.jpg" /usr/comma/bg.jpg
fi

echo "[3/3] 同步并重启生效..."
sudo sync
echo "完成！即将重启设备应用新启动图。"
sudo reboot
`;

/** 生成 tar 头（POSIX ustar） */
function tarHeader(name, size) {
  const block = new Uint8Array(512);
  const enc = new TextEncoder();
  const write = (str, offset, len) => {
    const bytes = enc.encode(str);
    for (let i = 0; i < len; i++) block[offset + i] = i < bytes.length ? bytes[i] : 0;
  };
  write(name, 0, 100);
  write('0000644', 100, 8);      // mode
  write('0000000', 108, 8);      // uid
  write('0000000', 116, 8);      // gid
  write(size.toString(8).padStart(11, '0'), 124, 12); // size
  write(Math.floor(Date.now() / 1000).toString(8).padStart(11, '0'), 136, 12); // mtime
  write('        ', 148, 8);     // checksum 占位
  block[156] = '0'.charCodeAt(0); // typeflag = file
  write('ustar', 257, 6);
  write('00', 263, 2);

  // 计算 checksum
  let sum = 0;
  for (let i = 0; i < 512; i++) sum += block[i];
  write(sum.toString(8).padStart(6, '0') + '\0 ', 148, 8);
  return block;
}

/** 构建 tar（未压缩） */
function buildTar(files) {
  const chunks = [];
  for (const { name, data } of files) {
    const u8 = data instanceof Uint8Array ? data : new Uint8Array(data);
    chunks.push(tarHeader(name, u8.length));
    chunks.push(u8);
    // 补齐到 512 字节
    const pad = (512 - (u8.length % 512)) % 512;
    if (pad) chunks.push(new Uint8Array(pad));
  }
  // tar 结束标记（两个空块）
  chunks.push(new Uint8Array(1024));
  // 合并
  let total = 0;
  for (const c of chunks) total += c.length;
  const out = new Uint8Array(total);
  let off = 0;
  for (const c of chunks) { out.set(c, off); off += c.length; }
  return out;
}

/**
 * 生成恢复包
 * @param {Object} p
 * @param {Uint8Array|null} p.splashBuffer  splash_custom.img
 * @param {ArrayBuffer|Uint8Array|null} p.bgBuffer  bg.jpg
 * @param {string} p.installScriptStr  安装脚本
 * @returns {Blob} tar.gz blob
 */
export function generateRestorePack({ splashBuffer, bgBuffer, installScriptStr = DEFAULT_INSTALL_SCRIPT }) {
  const enc = new TextEncoder();
  const files = [];
  if (splashBuffer) files.push({ name: 'agnos_c3_logo_restore_pack/splash_custom.img', data: splashBuffer });
  if (bgBuffer) files.push({ name: 'agnos_c3_logo_restore_pack/bg.jpg', data: new Uint8Array(bgBuffer) });
  files.push({ name: 'agnos_c3_logo_restore_pack/install_logo.sh', data: enc.encode(installScriptStr) });

  const tar = buildTar(files);
  const gz = gzipSync(tar, { level: 6 });
  return new Blob([gz], { type: 'application/gzip' });
}