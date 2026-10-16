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
