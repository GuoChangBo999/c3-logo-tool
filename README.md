# C3 超级投屏（独立版）

从 `xyz.yuluoqc.openpilottools` 里把 **C3 投屏**链路单独提取出来的独立 App。

## 它是怎么工作的（还原自原 App）

原 App 的 C3 投屏由三部分组成，本 App 完整复刻了客户端那半边：

```
[车机 comma 3 / 3X]                          [手机 本 App]
  weston_tiny_guard (守护 Weston)
  sde_rotator_stream  ── 抓屏 XRGB
        │
  swscale_xrgb_to_nv12 ── XRGB→NV12
        │
  v4l_h264_encoder ─── H264 编码, listen 0.0.0.0:5000  ──TCP──┐
                                                              │
  touch_proxy.py ────── 监听 0.0.0.0:27184  ◄──32B──┐        │
                                                    │        │
  ─────────────────── SSH (22) ─────────────────────┼────────┼──► SSHClient
                                                     │        │      │
                            本地端口转发 127.0.0.1:随机 ───────┘      │
                                                                      ▼
                                                    MirrorClient：收 H264 / 发触控
                                                                      │
                                                    H264Decoder：MediaCodec 硬解 → SurfaceView
```

- **视频**：车机 `v4l_h264_encoder` 在 `tcp://0.0.0.0:5000` 监听，推**裸 H264 Annex-B** 流
- **触控**：车机 `touch_proxy.py` 在 `27184` 监听，收 **32 字节定长包**
- **隧道**：手机用 SSH 本地端口转发，把车机 5000 / 27184 映射到手机本地，再连 `127.0.0.1`

### 触控协议（32 字节，大端）

| 偏移 | 类型 | 含义 |
|---|---|---|
| 0 | u8 | msg_type：`0x7F`=心跳，`2`=触控 |
| 1 | u8 | action：0=down 1=up 2=move 3=cancel |
| 2 | u64 | 时间戳（服务端忽略） |
| 10 | u32 | x |
| 14 | u32 | y |
| 18 | u16 | width（坐标空间宽） |
| 20 | u16 | height（坐标空间高） |
| 22 | u16 | pad |
| 24 | u32 | pad |
| 28 | u32 | pad |

> 心跳：手机发 `0x7F`，车机回 8 字节时间戳。

## 使用

1. 安装 APK
2. 填车机 IP、SSH 用户/密码（默认 `comma` / `comma`）
3. 勾选"连接前自动部署车机端"（首次必须勾）
4. 点"开始投屏"

App 会自动：
- SSH 连车机 → 上传内置的 `c3-screen-v2.tar` → 解压 → 跑 `install.sh`（启动推流+触控代理）
- 建立两条本地端口转发
- 收流 + 硬解 + 显示，并把触摸反控回车机

## 编译（GitHub Actions，推荐）

1. 把这个目录推到 GitHub 仓库（`main` 分支）
2. Actions 会自动触发 `Build APK`
3. 在 Actions 运行记录的 **Artifacts** 里下载 `c3mirror-debug-apk`

或者本地编译：`gradle assembleDebug`（需 JDK 17 + Android SDK 34）。

## 车机端要求

- 车机需 root（`install.sh` 用 `sudo -n`）
- 依赖 `python3`（touch_proxy.py）——comma 设备自带
- 车机端已内置在本 APK 的 `assets/c3-screen-v2.tar`，你也可手动替换

## 已知注意事项 / 待调

1. **首次编译可能需要微调 sshj 版本**（`0.38.0`）。若构建报错，改成 `0.37.0`。
2. **GitHub Actions 上首次跑**建议用 `workflow_dispatch` 手动触发一次。
3. **坐标映射**：客户端把视图坐标映射到 1024×512 再发给车机，车机端 `touch_proxy.py` 会再做 `1080-…/2160` 的旋转+镜像映射。若触摸位置不对，调 `MirrorActivity.SRC_W/SRC_H` 或车机端 `touch_proxy.py`。
4. **视频端口**：车机 `v4l_h264_encoder` 固定 listen 5000；触控固定 27184。
5. 本 App 只做了 **C3**；C2（scrcpy）那套未包含。

## SSH 认证方式

支持两种，可在主界面切换：

| 方式 | 说明 |
|---|---|
| **密码** | 默认 `comma` / `comma`（comma 车机默认） |
| **私钥（推荐）** | OpenSSH / PKCS#8 私钥，可带 passphrase |

- 勾选「使用 SSH 私钥认证」后，**优先尝试密钥**；密钥失败会自动回退到密码。
- 私钥可直接**粘贴**到输入框，或点「从文件选择私钥」选择 `id_rsa` / `id_ed25519`。
- 所有设置（IP / 用户 / 密钥内容 / 端口等）会自动保存，下次打开自动填充。

> 提示：密钥内容会存在 App 私有 SharedPreferences 里（未加密）。车机本身默认密码可用，
> 密钥主要用于你已给车机配置了公钥的场景。

---

## 竖屏 & X25519 修复（build-5）

### 1. App 改为竖屏
`AndroidManifest.xml` 里两个 Activity 的 `screenOrientation` 从 `landscape`
改为 `portrait`。因为车机画面是 1024x512（2:1 横屏），竖屏直显会被严重拉伸，
所以 `MirrorActivity` 里补了两件事：

* `H264Decoder` 调用 `setVideoScalingMode(VIDEO_SCALING_MODE_SCALE_TO_FIT)`，
  让 MediaCodec 自适应 Surface；
* `MirrorActivity.videoRect()` 计算 letterbox 后的真实画面矩形，触摸坐标按该
  矩形映射（而非整个 View），确保点击落点与所见一致。

### 2. `no such algorithm: X25519 for provider BC` 的真正根因
关键结论：**不是 BC 版本太旧，而是 BouncyCastle 的 Java-9 多版本 jar 在
Android 上失效。**

`bcprov-jdk18on` 的结构是：

```
org/bouncycastle/jcajce/provider/asymmetric/edec/KeyAgreementSpi$X25519.class      <- 303B  JDK8 “桩”
META-INF/versions/11/org/bouncycastle/.../edec/KeyAgreementSpi$X25519.class        <- 322B  真实现
META-INF/versions/11/org/bouncycastle/.../edec/KeyAgreementSpi.class               <- 8932B 真实现
org/bouncycastle/.../edec/KeyAgreementSpi.class                                    <- 6755B JDK8 版
```

桌面 JDK / ART 之外的场景会按 `META-INF/versions/11/` 覆盖查找；但 **Android 的
D8/R8 不实现 multi-release 语义**，所以只有根目录那份进 dex —— 即 JDK8 版。
再叠加早先 `packagingOptions` 把 `META-INF/versions/**` 整体排除，X25519 相关类
就彻底没了。实测 build-4 的 dex 里 `KeyAgreementSpi$X25519` 出现次数为 **0**。

### 3. 修复方式：把多版本 jar “拍平”
`tools/flatten_bc.py` 读取原始 bcprov/bcpkix/bcutil，把
`META-INF/versions/11/**` 下的 class **提升到 jar 根目录并覆盖同名桩类**，
同时丢弃 `versions/` 树（顺带消灭了之前 `DuplicateRelativeFileException`
的 OSGI manifest 冲突）。

结果：`app/libs/bcprov-jdk18on-1.79.jar` 里 `KeyAgreementSpi$X25519.class`
= 322B（真实现）、`KeyAgreementSpi.class` = 8932B（真实现），且**零重复条目**。

验证（本地 JDK 直接跑 provider 查询）：

```
OK   X25519 -> X25519
OK   X448   -> X448
OK   XDH    -> XDH
OK   Ed25519-> Ed25519
```

`build.gradle` 因此不再从 Maven 拉 BC，而是 `fileTree(dir: 'libs')`，
`packagingOptions` 也收窄为只排除 `META-INF/versions/**/OSGI-INF/MANIFEST.MF`。

### 4. 双保险：KEX 不依赖 BC 也能连
即便某个 ROM 上 BC 仍不可用，`SshDeployer` 现在也：

* 启动时 `Security.addProvider(new BouncyCastleProvider())` 并
  `SecurityUtils.registerSecurityProvider("BC")`；
* 用自定义 `DefaultConfig` 把 KEX 固定为 JDK 原生组
  （`ecdh-sha2-nistp521/384/256` + `diffie-hellman-group14` + `group-exchange-sha256`），
  完全绕开 curve25519。

两项都做过 javac 预检（含运行验证），确保不会再出现
`generic array creation` 之类的编译期意外。
