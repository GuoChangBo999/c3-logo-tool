# 🚀 C3 AGNOS 启动图工具 · 手机版

> comma 3 / 3X (C3 AGNOS) 开机启动图一键生成 · 刷入 · 备份 · 恢复
> 基于 **Vue 3 + Capacitor**，通过 GitHub Actions 云端自动编译 APK

---

## 📱 怎么拿到 APK？（傻瓜步骤）

### 第一步：注册/登录 GitHub
浏览器打开 https://github.com ，没账号就注册一个（免费）。

### 第二步：新建仓库
1. 点右上角 **+** → **New repository**
2. 名字随便填，例如 `c3-logo-tool`
3. 选 **Public**（公开），点 **Create repository**

### 第三步：上传本项目文件
1. 在新建的仓库页面，点 **uploading an existing file**
2. 把本项目**所有文件和文件夹**拖进去（注意 `.github` 文件夹也要传！）
   - ⚠️ 如果你是在手机上操作，可以用 GitHub 网页版逐个上传，或用 GitHub 手机 App
3. 点 **Commit changes**

> 💡 **更推荐的方式**：把整个 `c3logo` 文件夹用「GitHub Desktop」或手机上的 Git 工具 push 上去。

### 第四步：等云端自动编译
1. 上传后，进入仓库的 **Actions** 标签
2. 会看到 **Build Android APK** 正在跑（黄色圆点）
3. 等大约 **5～10 分钟**，变成 ✅ 绿色
4. 点进去 → 页面下方 **Artifacts** → 下载 **c3-logo-tool-debug-apk**
5. 解压，里面就是 `app-debug.apk`

### 第五步：安装
把 apk 传到手机，点击安装（需允许"未知来源"）。

---

## 🖥️ 电脑本地编译（可选）

需要 Node.js 20 + JDK 17 + Android SDK：

```bash
npm install
npm run build
npx cap add android
npx cap sync android

# 注入原生 SSH 插件
mkdir -p android/app/src/main/java/com/openpilot/c3logo
cp native-overlay/C3SshPlugin.java android/app/src/main/java/com/openpilot/c3logo/
cp native-overlay/MainActivity.java android/app/src/main/java/com/openpilot/c3logo/

# 注入 SSHJ 依赖（手动编辑 android/app/build.gradle 的 dependencies 块，加入）
#   implementation 'com.hierynomus:sshj:0.38.0'
#   implementation 'org.bouncycastle:bcprov-jdk18on:1.78'
#   implementation 'org.bouncycastle:bcpkix-jdk18on:1.78'
#   implementation 'com.jcraft:jzlib:1.1.3'
#   implementation 'net.i2p.crypto:eddsa:0.3.0'

cd android && ./gradlew assembleDebug
# 产物: android/app/build/outputs/apk/debug/app-debug.apk
```

---

## ✨ 功能

| 功能 | 说明 |
|------|------|
| 🎨 生成第一屏 | 图片 → 1080×2160 24-bit BMP → `splash_custom.img` (34,226,176 字节) |
| 🎨 生成第二屏 | 图片 → `/usr/comma/bg.jpg` (1080×2160 JPEG) |
| 📦 恢复包 | 生成 `agnos_c3_logo_restore_pack.tar.gz` |
| 🔌 SSH 连接 | 密码 / OpenSSH 密钥 / PuTTY PPK |
| ⚡ 一键刷入 | 备份 → 上传 → 执行 → 重启 |

---

## 🛠 技术架构

- **前端**：Vue 3 + Vite
- **外壳**：Capacitor 6 → Android
- **图像引擎**：纯 JS（Canvas + 手写 BMP 编码，无 sharp）
- **打包引擎**：fflate（纯 JS tar.gz）
- **SSH**：原生 Java 插件 + SSHJ 库（支持 OpenSSH/PuTTY PPK）
- **CI/CD**：GitHub Actions 自动编译 APK

---

## ⚠️ 注意事项

1. **首次编译**会在 GitHub 云端自动 `cap add android`，不用你手动建 android 目录
2. **SSH 主机密钥**：为方便连接 C3 设备，使用了 `PromiscuousVerifier`（接受任意主机密钥）。仅建议在可信局域网使用
3. **刷机有风险**：刷入前 App 会自动备份原厂图到 `/data/agnos_logo_tool/backup/`
4. **权限**：App 需要网络权限（SSH 连接）

---

## 📜 开源协议

MIT License · 基于原项目 `c3-agnos-logo-tool` 移植

Copyright © 2026
