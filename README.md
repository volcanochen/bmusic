# BMusic

把 B 站视频当音乐听：分享链接 → 解析入库 → 后台播放 / 缓存离线听。

一个不依赖 Gradle、不依赖任何第三方库的原生 Android 音乐播放器（只有 Android SDK + JDK）。
全部界面、网络、播放、缓存、数据库都是手写的，APK 约 130 KB。

## 功能

- **分享即添加**：在 B 站 App / 网页点分享 → 选择 BMusic；或复制链接后回到「曲库」页点右上角 `+` 粘贴（支持整段分享文案、BV 号、av 号、b23.tv 短链）
- **自动解析**：走 `view` 接口取标题 / UP 主 / 封面 / 时长，多 P 视频按分 P 精确取对应 `cid`
- **音频提取**：`playurl` + DASH 取纯音频流（不对视频做转码，拿的就是 192K/132K/64K 那条音轨）
- **后台播放**：前台服务 + MediaSession + MediaStyle 通知（上一首 / 暂停 / 下一首），锁屏和耳机线控可用
- **缓存离线**：一键缓存到本地，播放时本地优先；可自动缓存下一首、可限制仅 WiFi 缓存
- **歌单体系**：自建歌单 + 内置「我喜欢的音乐」，支持加入 / 移除 / 排序 / 重命名 / 清空
- **播放器界面**：模糊封面背景、大封面、进度拖拽、播放模式（列表循环 / 单曲循环 / 随机）、喜欢、播放队列
- **播放历史**：最近播放列表 + 播放次数统计
- **搜索**：本地按标题 / UP 主 / BV 号过滤

## 技术要点

| 模块 | 实现 |
| --- | --- |
| 构建 | `aapt2 → javac → d8 → zipalign → apksigner`，纯命令行脚本 `build.sh`，无需 Gradle |
| 网络 | `HttpURLConnection` + `java.net.CookieManager`（自动维护 buvid3 / b_nut） |
| 签名 | 完整 **wbi 签名**（mixin key 混淆表 + MD5），未登录也能稳定取流 |
| 播放 | `MediaPlayer`（`setDataSource` 带 Referer/UA 头播放 CDN 上的 m4s，本地文件直读） |
| 控制 | framework `MediaSession` + `Notification.MediaStyle` + AudioFocus + `ACTION_AUDIO_BECOMING_NOISY` |
| 数据 | `SQLiteOpenHelper`：tracks / playlists / pl_tracks / history |
| 图片 | 自写封面加载器（LruCache + 磁盘缓存 + 手写 stack blur 做播放页背景） |

## 构建

需要 Android SDK（build-tools 34、platform android-34）与 JDK 17：

```bash
bash build.sh          # 产出 build/bmusic.apk
adb install -r build/bmusic.apk
```

脚本会在首次构建时自动生成 `debug.keystore`。

## 使用

1. 把它设为 B 站分享的目标应用，或在曲库页粘贴链接
2. 点条目播放；右侧 `⋮`（或长按）可加歌单 / 缓存 / 删除
3. 播放页可以缓存、加入歌单、切播放模式
4. 「我的」页可看缓存占用；「设置」里可填 B 站 Cookie 解锁更高码率

> Cookie 是可选的。不填时接口仍能取到音频流（通常是 64K/132K），填了 `SESSDATA` 后可以拿到更高码率的音轨。

## 说明

音频内容版权归哔哩哔哩及原 UP 主所有，本项目仅用于个人设备上离线收听已公开的内容，请勿用于分发或商业用途。
