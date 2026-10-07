<template>
  <div class="app">
    <header class="hd">
      <h1>🚀 C3 AGNOS 启动图工具</h1>
      <p class="sub">comma 3 / 3X 开机图一键生成 · 刷入 · 备份</p>
    </header>

    <!-- 连接配置 -->
    <section class="card">
      <h2>① 设备连接</h2>
      <div class="row"><label>IP</label><input v-model="ssh.host" placeholder="192.168.0.34" /></div>
      <div class="row"><label>端口</label><input v-model="ssh.port" placeholder="22" /></div>
      <div class="row"><label>用户</label><input v-model="ssh.username" placeholder="comma" /></div>
      <div class="row"><label>密码</label><input v-model="ssh.password" type="password" placeholder="(密码认证, 可留空走密钥)" /></div>
      <p class="hint">支持密码认证；密钥/PPK 认证在 App 内选择文件。</p>
    </section>

    <!-- 图片选择 -->
    <section class="card">
      <h2>② 选择启动图</h2>
      <div class="row">
        <label>第一屏</label>
        <input type="file" accept="image/*" @change="onPick($event, 'first')" />
      </div>
      <div class="row">
        <label>第二屏</label>
        <input type="file" accept="image/*" @change="onPick($event, 'second')" />
      </div>
      <div class="row">
        <label>填充</label>
        <select v-model="fitMode">
          <option value="contain">contain（等比留黑）</option>
          <option value="cover">cover（裁剪填满）</option>
        </select>
      </div>

      <div class="preview" v-if="preview.first">
        <p>第一屏预览：</p><img :src="preview.first" />
      </div>
      <div class="preview" v-if="preview.second">
        <p>第二屏预览：</p><img :src="preview.second" />
      </div>
    </section>

    <!-- 操作 -->
    <section class="card">
      <h2>③ 操作</h2>
      <div class="btns">
        <button :disabled="busy" @click="doGenerate">🎨 仅生成镜像 (本地)</button>
        <button :disabled="busy" @click="doExportPack">📦 生成恢复包 (.tar.gz)</button>
        <button class="primary" :disabled="busy" @click="doTest">🔌 测试连接</button>
        <button class="danger" :disabled="busy" @click="doFlash">⚡ 一键刷入并重启</button>
      </div>
    </section>

    <!-- 日志 -->
    <section class="card">
      <h2>④ 日志</h2>
      <pre class="log">{{ log }}</pre>
    </section>

    <footer class="ft">Copyright © 2026 · C3 AGNOS Logo Tool Mobile · MIT</footer>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue';
import {
  processFirstScreen, processSecondScreen,
  SPLASH_IMG_SIZE, BMP_EXACT_SIZE,
} from './engines/bmpEngine.js';
import { generateRestorePack } from './engines/packEngine.js';
import { connectSsh, execSshCommand, uploadBytes } from './bridge.js';

const ssh = reactive({ host: '192.168.0.34', port: '22', username: 'comma', password: '' });
const fitMode = ref('contain');
const busy = ref(false);
const log = ref('');
const files = reactive({ first: null, second: null });
const preview = reactive({ first: '', second: '' });

function println(s) { log.value += s + '\n'; }

function onPick(e, which) {
  const f = e.target.files[0];
  if (!f) return;
  files[which] = f;
  const reader = new FileReader();
  reader.onload = () => { preview[which] = reader.result; };
  reader.readAsDataURL(f);
}

/** 把 File 读成 HTMLImageElement */
function loadImage(file) {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file);
    const img = new Image();
    img.onload = () => { URL.revokeObjectURL(url); resolve(img); };
    img.onerror = (e) => { URL.revokeObjectURL(url); reject(new Error('图片加载失败')); };
    img.src = url;
  });
}

/** 生成镜像 buffers */
async function buildImages() {
  let splashBuffer = null, bgBuffer = null;
  if (files.first) {
    println('🎨 处理第一屏...');
    const img = await loadImage(files.first);
    const { splash } = processFirstScreen(img, fitMode.value);
    if (splash.length !== SPLASH_IMG_SIZE) throw new Error('splash 尺寸不符');
    splashBuffer = splash;
    println(`  └─ splash_custom.img = ${splash.length} 字节 ✓`);
  }
  if (files.second) {
    println('🎨 处理第二屏...');
    const img = await loadImage(files.second);
    bgBuffer = await processSecondScreen(img);
    println(`  └─ bg.jpg = ${bgBuffer.byteLength} 字节 ✓`);
  }
  if (!splashBuffer && !bgBuffer) throw new Error('请至少选择一张图片！');
  return { splashBuffer, bgBuffer };
}

function saveBlob(blob, name) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url; a.download = name;
  document.body.appendChild(a); a.click();
  document.body.removeChild(a);
  setTimeout(() => URL.revokeObjectURL(url), 5000);
}

async function doGenerate() {
  busy.value = true; log.value = '';
  try {
    const { splashBuffer, bgBuffer } = await buildImages();
    if (splashBuffer) saveBlob(new Blob([splashBuffer]), 'splash_custom.img');
    if (bgBuffer) saveBlob(new Blob([bgBuffer], { type: 'image/jpeg' }), 'bg.jpg');
    println('✅ 已导出到手机下载目录');
  } catch (e) { println('❌ ' + e.message); }
  finally { busy.value = false; }
}

async function doExportPack() {
  busy.value = true; log.value = '';
  try {
    const { splashBuffer, bgBuffer } = await buildImages();
    println('📦 打包中...');
    const blob = generateRestorePack({ splashBuffer, bgBuffer });
    saveBlob(blob, 'agnos_c3_logo_restore_pack.tar.gz');
    println('✅ 恢复包已导出');
  } catch (e) { println('❌ ' + e.message); }
  finally { busy.value = false; }
}

async function doTest() {
  busy.value = true; log.value = '';
  try {
    println(`🔌 连接 ${ssh.host}:${ssh.port} ...`);
    await connectSsh(ssh);
    const out = await execSshCommand('ls -la /dev/disk/by-partlabel/splash && echo OK');
    println(out);
    println('✅ 设备可用');
  } catch (e) { println('❌ ' + e.message); }
  finally { busy.value = false; }
}

async function doFlash() {
  busy.value = true; log.value = '';
  try {
    const { splashBuffer, bgBuffer } = await buildImages();
    println('🔑 连接 SSH...');
    await connectSsh(ssh);
    println('📦 备份原厂图...');
    await execSshCommand('mkdir -p /data/agnos_logo_tool/backup');
    if (splashBuffer) {
      await execSshCommand('sudo dd if=/dev/disk/by-partlabel/splash of=/data/agnos_logo_tool/backup/splash_backup.img bs=1M');
    }
    if (bgBuffer) {
      await execSshCommand('sudo cp /usr/comma/bg.jpg /data/agnos_logo_tool/backup/bg_backup.jpg 2>/dev/null || true');
    }
    println('📤 上传镜像...');
    if (splashBuffer) await uploadBytes(splashBuffer, '/data/agnos_logo_tool/splash_custom.img');
    if (bgBuffer) await uploadBytes(new Uint8Array(bgBuffer), '/data/agnos_logo_tool/bg.jpg');
    println('⚡ 执行刷入脚本...');
    const script = await import('./engines/packEngine.js').then(m => m.DEFAULT_INSTALL_SCRIPT);
    await uploadBytes(new TextEncoder().encode(script), '/data/agnos_logo_tool/install_logo.sh');
    await execSshCommand('sudo chmod +x /data/agnos_logo_tool/install_logo.sh && sudo sh /data/agnos_logo_tool/install_logo.sh');
    println('🎉 刷入指令已下发，设备将重启！');
  } catch (e) { println('❌ ' + e.message); }
  finally { busy.value = false; }
}
</script>

<style>
* { box-sizing: border-box; }
body { margin: 0; background: #090d16; color: #e6edf3;
  font-family: -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif; }
.app { max-width: 720px; margin: 0 auto; padding: 16px 14px 40px; }
.hd h1 { font-size: 20px; margin: 0 0 4px; }
.hd .sub { color: #8b949e; font-size: 13px; margin: 0 0 16px; }
.card { background: #0d1420; border: 1px solid #1c2533; border-radius: 12px;
  padding: 14px; margin-bottom: 14px; }
.card h2 { font-size: 15px; margin: 0 0 12px; color: #58a6ff; }
.row { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; }
.row label { width: 62px; color: #8b949e; font-size: 13px; flex: none; }
.row input, .row select { flex: 1; background: #070b12; border: 1px solid #263042;
  color: #e6edf3; border-radius: 8px; padding: 9px 10px; font-size: 14px; min-width: 0; }
.hint { color: #6e7681; font-size: 12px; margin: 4px 0 0; }
.preview { margin-top: 10px; }
.preview p { font-size: 13px; color: #8b949e; margin: 6px 0; }
.preview img { max-width: 100%; max-height: 260px; border-radius: 8px; border: 1px solid #263042; }
.btns { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.btns button { padding: 12px; border-radius: 10px; border: 1px solid #263042;
  background: #16202e; color: #e6edf3; font-size: 14px; cursor: pointer; }
.btns button.primary { background: #1f6feb; border-color: #1f6feb; }
.btns button.danger { background: #da3633; border-color: #da3633; grid-column: span 2; }
.btns button:disabled { opacity: .5; }
.log { background: #05080d; border: 1px solid #1c2533; border-radius: 8px;
  padding: 12px; font-size: 12px; line-height: 1.6; color: #7ee787;
  white-space: pre-wrap; word-break: break-all; min-height: 80px; max-height: 300px; overflow: auto; margin: 0; }
.ft { text-align: center; color: #484f58; font-size: 12px; margin-top: 20px; }
</style>