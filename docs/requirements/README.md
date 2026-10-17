# BMusic 需求清单与实现对照（v1.0.0 → v1.0.7）

本文档汇总 BMusic 全部需求点，按主题归类。需求来源分两类：

- **用户**：用户在迭代过程中明确提出的需求
- **自主**：开发/联调过程中发现的问题、缺陷或体验改进，由 AI 主动提出并实现

每条需求附实现方式简述与涉及的主要代码。版本列表示该需求随哪个版本交付。

---

## 一、分类总览

| 分类 | 主题 | 需求条数 | 主要交付版本 |
| :-- | :-- | --: | :-- |
| A | 链接获取与导入 | 4 | v1.0.0 |
| B | 播放核心 | 6 | v1.0.0 / v1.0.4 |
| C | 界面与窗口适配 | 7 | v1.0.0 / v1.0.2 / v1.0.5 |
| D | 播放队列与曲库操作 | 6 | v1.0.6 / v1.0.7 |
| E | 歌词 | 3 | v1.0.4 / v1.0.6 |
| F | 音量控制 | 1 | v1.0.4 |
| G | 离线缓存 | 4 | v1.0.0 / v1.0.3 |
| H | 缺陷修复与工程加固 | 8 | 随版本持续 |

---

## 二、需求明细与实现对照

### A. 链接获取与导入

| ID | 需求 | 来源 | 版本 | 实现方式 | 主要代码 |
| :-- | :-- | :-- | :-- | :-- | :-- |
| A1 | B 站 App「分享 → BMusic」一键入库 | 用户 | v1.0.0 | `ShareActivity` 注册 `ACTION_SEND` / `ACTION_VIEW` intent-filter，透明弹窗式呈现，提取链接后可选「立即播放 / 添加 / 下一首播放」 | `ui/ShareActivity`、`AndroidManifest` |
| A2 | 复制整段分享文案，粘贴导入；自动识别 `BV号 / av号 / b23.tv短链 / 完整链接` | 用户 | v1.0.0 | 正则从任意文本提取多种链接形态；短链先 HEAD 跟随 302 拿真实 BV 号 | `api/Bili.resolve`、`api/Importer` |
| A3 | 多 P 视频按分 P 精确导入，曲目 id = `bvid_pN` | 用户 | v1.0.0 | 解析 `view` 接口取全部分页，逐页入库；播放时按 page 定位对应 cid | `api/Bili`、`db/Db.upsert` |
| A4 | 填 Cookie 解锁 192K 高码率 | 用户 | v1.0.0 | 设置页保存 SESSDATA 等，`Http` 全局 CookieManager 带出 | `ui/SettingsActivity`、`net/Http` |
| A5 | 系统「打开方式」里的两个 BMusic 入口提供图形+文字指引（原两个都叫 BMusic 分不清） | 用户 | v1.0.9 | intent-filter 级 `android:label`/`android:icon` 覆盖：MainActivity=「打开 BMusic 播放」(▶ 徽标 `ic_badge_play`，进应用导入)，ShareActivity=「添加到 BMusic 曲库」(+ 徽标 `ic_badge_add`，弹快捷菜单) | `AndroidManifest`、`drawable/ic_badge_*` |

### B. 播放核心

| ID | 需求 | 来源 | 版本 | 实现方式 | 主要代码 |
| :-- | :-- | :-- | :-- | :-- | :-- |
| B1 | 只取音频流播放（不下载视频） | 用户 | v1.0.0 | wbi 签名调 `x/player/playurl`（fnval=16），取 DASH 音轨，码率优先级 30280 > 30232 > 30216（跳过 30250 杜比） | `api/Wbi`、`api/Bili`、`player/PlaybackService.startSource` |
| B2 | 后台 / 锁屏 / 息屏不中断 | 用户 | v1.0.0 | 前台服务常驻 + `setWakeMode(PARTIAL_WAKE_LOCK)` | `player/PlaybackService` |
| B3 | 通知栏 / 耳机线控 / 蓝牙控制 | 用户 | v1.0.0 | `MediaSession` + `MediaStyle` 通知（上一首/暂停/下一首），注册媒体按键与 `AUDIO_BECOMING_NOISY` | `player/PlaybackService.initSession`、`util/Notify` |
| B4 | 三种播放模式：列表循环 / 单曲循环 / 随机 | 用户 | v1.0.0 | `next()` 按 mode 分支；模式持久化到 SharedPreferences | `player/PlaybackService.next` |
| B5 | 播放页完整体验：进度拖拽、模糊封面背景、分 P 徽标 | 用户 | v1.0.0 | `MediaPlayer` 进度轮询；封面缩 1/8 后纯 Java `fastBlur` 堆栈模糊（不用 RenderScript） | `ui/PlayerActivity`、`util/CoverLoader` |
| B6 | 应用重启后点播放无反应修复 | 自主 | v1.0.4 | `play()` 检测 `mp==null || !mpReady` 时自动 `startSource` 重新准备再播 | `player/PlaybackService.play` |

### C. 界面与窗口适配

| ID | 需求 | 来源 | 版本 | 实现方式 | 主要代码 |
| :-- | :-- | :-- | :-- | :-- | :-- |
| C1 | 主界面三页（曲库 / 歌单 / 我的）+ 迷你条常驻 | 用户 | v1.0.0 | 单 Activity `FrameLayout` 叠页 + 自绘底部导航，切换无重建 | `ui/MainActivity` |
| C2 | 适配屏幕旋转 | 用户 | v1.0.2 | `onConfigurationChanged` 切换布局，播放不中断 | `ui/PlayerActivity` |
| C3 | 分屏 / 浮窗按分配的窗口比例刷新、内容重排 | 用户 | v1.0.5 | 四 Activity 声明 `resizeableActivity` + 完整 `configChanges`（含 `smallestScreenSize`）不重建；`util/Win` 按 WindowMetrics 度量窗口；宽高比变化时 `setContentView` 换布局并缩放控件；按钮被截断时实测（`getEllipsisCount`）去图标 | `util/Win`、`ui/PlayerActivity.relayoutForWindow`、`applyWindowSize` |
| C4 | 多窗口下度量必须用窗口尺寸而非屏幕尺寸 | 自主 | v1.0.5 | `getDefaultDisplay()` 在分屏下返回屏幕尺寸，改用 API30+ `getCurrentWindowMetrics()`，低版本回退 `Configuration.screenWidthDp/HeightDp` | `util/Win` |
| C5 | 迷你条显示播放状态并提醒播放事件（缓冲中/播放中/已暂停 + 进度线），标题切换动画过渡 | 用户 | v1.0.6 | 状态驱动文案与图标；进度线随下载/播放进度填充；标题/副标题淡入淡出+位移（`animateText`），播放键脉冲缩放 | `ui/MainActivity.refreshMini` |
| C6 | 播放页「播放队列」按钮移到底部方便点击 | 用户 | v1.0.6 | 从顶部标题栏移到播放页底部操作区 | `res/layout/activity_player*.xml` |
| C7 | 播放页底部「返回 B 站」按钮 | 用户 | v1.0.7 | 与「播放队列」并排（各占 50%），点击 `ACTION_VIEW` 跳转 B 站 | `ui/PlayerActivity`、`res/drawable/ic_bili` |
| C8 | 最近播放与「我的」拆成两个独立入口/页面 | 用户 | v1.0.8 | 底部导航变四页（曲库/歌单/最近/我的）；`page_recent` 独立页（标题+全屏列表），`page_me` 只留空间管理/设置/应用更新 | `res/layout/activity_main.xml`、`ui/MainActivity.initTabs/select` |
| C9 | 「我的」页提供应用内更新（GitHub 直链始终最新） | 用户 | v1.0.8 | 系统自带 `DownloadManager` 下载 release/bmusic.apk（带通知+完成态），轮询状态成功后 `getUriForDownloadedFile` + `ACTION_VIEW` 拉起安装器；manifest 加 `REQUEST_INSTALL_PACKAGES`；首次安装需用户在系统里允许「安装未知应用」 | `ui/MainActivity.startAppUpdate/pollUpdate/installApk`、`AndroidManifest` |

### D. 播放队列与曲库操作

| ID | 需求 | 来源 | 版本 | 实现方式 | 主要代码 |
| :-- | :-- | :-- | :-- | :-- | :-- |
| D1 | 曲库点击不自动播放（点击=加入队列） | 用户 | v1.0.3 | 行点击改调 `PlaybackService.append`；播放为显式动作（⋮ 菜单「立即播放」） | `ui/MainActivity`、`ui/adapter/TrackAdapter` |
| D2 | 播放列表独立成页，按曲库样式设计，标记正在播放，提供同类操作 | 用户 | v1.0.6 | 新建 `QueueActivity` 复用 `item_track` 行与 `TrackAdapter`；当前曲目 ▶ 高亮；菜单=立即播放/上移/下移/缓存/加入歌单/返回B站/移除/删除 | `ui/QueueActivity`、`res/layout/activity_queue.xml` |
| D3 | 「立即播放」= 加入当前播放列表并立即播放 | 用户 | v1.0.6 | `insertAndPlay`：不在队列则插到当前曲之后，随即 `openIndex` 播放；已在队列直接切换 | `player/PlaybackService`（A_PLAY_NOW） |
| D4 | 曲库「播放 N 首」：当前筛选范围全部导入播放列表并播放 | 用户 | v1.0.6 | `replaceQueue`（A_QUEUE_KEEP）：整体替换队列内容但保留正在播放的曲目不打断；当前曲在筛选结果内则继续播，否则从第一首播 | `ui/MainActivity.playFiltered`、`player/PlaybackService` |
| D5 | 队列编辑：上移 / 下移 / 移除 | 用户 | v1.0.6 | `editQueue(id, op)`：op=0 移除、∓1 移动；重算 index 保持当前曲不跳变并持久化 | `player/PlaybackService.editQueue` |
| D6 | 曲目「返回 B 站」：从曲库/歌单/队列跳回 B 站该曲目页面 | 用户 | v1.0.7 | `TrackActions.openBili`：按 `bvid` 组装 `https://www.bilibili.com/video/BVxxx`，多 P 追加 `?p=N`；`ACTION_VIEW` 由系统分发到浏览器或 B 站 App；四个列表菜单与播放页按钮统一接入 | `ui/TrackActions.openBili`、四个 Activity 菜单 |
| D7 | 队列控制行为：清空队列 + 勾选曲目批量删除 | 用户 | v1.0.8 | 顶栏 ✓ 进入多选（行首 checkbox、标题显示「已选 n/N」、批量删除确认框）、🗑 清空（确认框，清空并暂停）；服务端 `clearQueue()` / `removeIds()`（A_QUEUE_CLEAR / A_QUEUE_RM_IDS） | `ui/QueueActivity`、`player/PlaybackService`、`adapter/TrackAdapter`（多选支持） |
| D8 | 播放页布局重排：缓存+返回B站上移至封面之上，下方一行=加入歌单/歌词/播放队列；顶部返回箭头改为「曲库 · N」入口按钮（N=曲库总数） | 用户 | v1.0.8 | 竖屏/横屏两套布局同步调整；曲库按钮沿用 `back` id（finish 回主界面），总数进入页面时后台查询一次 | `res/layout/activity_player*.xml`、`ui/PlayerActivity` |

### E. 歌词

| ID | 需求 | 来源 | 版本 | 实现方式 | 主要代码 |
| :-- | :-- | :-- | :-- | :-- | :-- |
| E1 | 自动联网找歌词并在播放界面逐行同步展示 | 用户 | v1.0.4 | 网易云免加密 GET 接口（cloudsearch 搜索 → song/lyric 取词），LRC 解析 + 播放进度驱动高亮滚动；结果缓存到 `getCacheDir()/lyrics`；面板支持手动搜索更换 | `lyric/NeteaseLyric`、`lyric/Lyric`、`ui/PlayerActivity` |
| E2 | 歌词搜索栏预填「歌手 歌名」，不含编号与 UP 主名 | 用户 | v1.0.6 | `suggestQuery(title, author)`：去括号标签 → 去 `P1/第N集/BV号/EP/CD/4K` 等编号噪声 → 剥离行首序号 → 「序号歌手 - 歌名」拆分为歌手+歌名组合，去重不重复拼接 | `lyric/NeteaseLyric.suggestQuery` |
| E3 | 换歌自动刷新歌词搜索词与内容 | 自主 | v1.0.6 | `fetchLyricFor(cur)` 以 `lastLyricKey` 判断换曲自动重查 | `ui/PlayerActivity` |
| E4 | 搜索结果过滤明显不相关的标题 | 用户 | v1.0.8 | `relevant(query, name)`：标题与查询词字符重合度 ≥50% 才保留（空白忽略、不分大小写） | `lyric/NeteaseLyric.relevant` |
| E5 | 名称正确但取词失败的候选自动跳过 | 用户 | v1.0.8 | `getLyric` 对过滤后的候选逐个尝试取词（404/空歌词作废），第一个成功的才使用；全部失败才报「无可用歌词」 | `lyric/NeteaseLyric.getLyric` |

### F. 音量控制

| ID | 需求 | 来源 | 版本 | 实现方式 | 主要代码 |
| :-- | :-- | :-- | :-- | :-- | :-- |
| F1 | 纵向音量条：既是调节 bar 也是显示 bar | 用户 | v1.0.4 | 自定义 `VolumeBar` View：竖向拖动改 `STREAM_MUSIC`，即时显示 `90/160`；500ms 轮询与系统/按键音量双向同步 | `ui/VolumeBar` |

### G. 离线缓存

| ID | 需求 | 来源 | 版本 | 实现方式 | 主要代码 |
| :-- | :-- | :-- | :-- | :-- | :-- |
| G1 | 缓存到本地，播放本地优先 | 用户 | v1.0.0 | `Downloader` 流式写盘；`startSource` 有本地文件直接 `setDataSource(path)`，否则带 Referer/UA 头拉 CDN 流 | `util/Downloader`、`player/PlaybackService.startSource` |
| G2 | 缓存进度与速度在明显位置展示，并把状态+动作+进度+速度整合进一个按钮 | 用户 | v1.0.2 / v1.0.3 | 「缓存到本地」按钮整合四种状态：去缓存 / 缓存中 NN% · X KB/s（带填充背景）/ 已缓存 ✓ / 失败可重试；`ACT_DL` 广播驱动 | `ui/PlayerActivity`、`util/Downloader` |
| G3 | 自动缓存下一首（可仅 WiFi） | 用户 | v1.0.0 | 切歌时后台预取下一首；`ConnectivityManager` 判断网络类型 | `util/Downloader` |
| G4 | 空间管理：缓存占用显示与一键清理 | 用户 | v1.0.0 | 「我的」页实时统计音频/封面目录大小，提供清理入口 | `ui/MainActivity` |
| G5 | 自动开启缓存：起播即自动缓存当前曲目 | 用户 | v1.0.8 | `openIndex` 时对未缓存曲目自动 `Downloader.start`（内部去重+本地检查）；受「仅WiFi缓存」约束（`Downloader.autoAllowed`），不满足则静默跳过 | `player/PlaybackService.autoCache`、`util/Downloader.autoAllowed` |

### H. 缺陷修复与工程加固

| ID | 问题 | 来源 | 版本 | 修复方式 |
| :-- | :-- | :-- | :-- | :-- |
| H1 | Android 13+ 动态注册广播缺导出级别崩溃；内部广播收不到（播放 UI 不刷新） | 自主 | v1.0.2 | 统一走 `util/Receivers.registerLocal()`（SDK≥33 用 `RECEIVER_NOT_EXPORTED`）；内部广播发送方 `setPackage(getPackageName())` |
| H2 | 播放失败 -38 错误 | 自主 | v1.0.2 | `MediaPlayer` 状态机错位（未 reset 复用/流失效），重取流前 reset 并校验 URL 时效 |
| H3 | 一打开就闪退（NoClassDefFoundError） | 自主 | v1.0.3 | build.sh 的 javac 失败被管道静默吞掉，产出缺类 APK；脚本加固为 javac 失败立即退出报错 |
| H4 | ListView 行内 clickable 子 View（⋮ 按钮）导致整行 `setOnItemClickListener` 失效 | 自主 | v1.0.0 | 不依赖 ListView item click，Adapter `getView()` 里直接给整行绑点击（`TrackAdapter.Listener.onPlay`） |
| H5 | 无法使用 lambda（`-bootclasspath android.jar` 无 LambdaMetafactory） | 自主 | 构建约束 | 全项目匿名内部类；排序用 `Collections.sort` + 匿名 `Comparator` |
| H6 | 分屏/浮窗每次改尺寸都重建 Activity | 自主 | v1.0.5 | `configChanges` 漏了 `smallestScreenSize`，补齐后不再重建 |
| H7 | 冷启动后播放队列页为空（服务未运行，`PlaybackService.get()` 为 null） | 自主 | v1.0.6 | 新增 `PlaybackService.wake()`（无 action 的 `startForegroundService`，onCreate 会 restore 队列）；MainActivity/PlayerActivity/QueueActivity 按需唤醒 |
| H8 | 布局属性 `hintTextColor` 不存在 | 自主 | v1.0.4 | 改用 `android:textColorHint` |
| H9 | 桌面小组件点按钮无反应（`Background start not allowed: ... startFg?=false`） | 自主 | v1.0.9 | Android 12+ 从桌面 `PendingIntent.getService` 启动服务被拦截；API 26+ 改用 `PendingIntent.getForegroundService`（服务 onCreate 即 startForeground，满足 5s 要求），API 24/25 仍走 getService |
| H10 | b23.tv 短链走不进 App（`query-activities` 查不到 BMusic） | 自主 | v1.0.9 | 同一 intent-filter 内多个 `<data>` 是合并求交：`/video/` pathPrefix 约束被应用到 `b23.tv` 上。把 b23.tv / www.b23.tv 拆成独立 intent-filter（无 path 约束）后短链恢复匹配 |

### I. 桌面小组件（v1.0.9）

| ID | 需求 | 来源 | 版本 | 实现方式 |
| :-- | :-- | :-- | :-- | :-- |
| W1 | 2×2 桌面小组件，只有一个大号播放/暂停按钮；点按钮以外的空间进入播放页 | 用户 | v1.0.9 | `ui/WidgetProvider`（AppWidgetProvider）：根布局 PendingIntent → PlayerActivity（NEW_TASK|SINGLE_TOP），居中 120dp 粉色圆钮 PendingIntent → 服务 `A_TOGGLE`；`res/xml/widget_play_info.xml`（minWidth/Height 110dp）+ `layout/widget_play.xml` + `drawable/widget_bg|widget_btn` |
| W2 | 小组件图标实时反映播放状态 | 自主 | v1.0.9 | 服务 `ACT_STATE` 广播（包内显式）加入 WidgetProvider intent-filter，onReceive 里 refreshAll；`onUpdate` 初始渲染按 `PlaybackService.get().isPlaying()` 取图标（服务未运行显示播放键） |

---

## 三、需求 → 实现要点速查表（按用户原始需求顺序）

| # | 用户需求（原意） | 版本 | 实现要点 |
| --: | :-- | :-- | :-- |
| 1 | GitHub 直链下载安装 | v1.0.0 | `release/` 目录随仓库发布 + GitHub Releases 附件 + debug.keystore 入仓保证签名一致可覆盖安装 |
| 2 | N6 真机验证（重点 B 站分享） | 持续 | adb/uiautomator/screencap 自动化验证流程，见 docs/TECHNICAL.md 第 10 节 |
| 3 | 修播放不成功 / 播放键与进度不刷新 / 屏幕旋转 | v1.0.2 | 取流头修复、状态广播修复、旋转布局切换 |
| 4 | 缓存进度与速度明显展示 | v1.0.2 | 下载广播 `ACT_DL` → 列表百分比 / 按钮文案 |
| 5 | 修 -38 错误 | v1.0.2 | MediaPlayer 状态机加固 |
| 6 | 缓存按钮整合状态/动作/进度/速度；曲库点击不自动播放 | v1.0.3 | 四态按钮；行点击=加入队列 |
| 7 | 修启动闪退 | v1.0.3 | 构建脚本 javac 加固 |
| 8 | 自动联网歌词并展示 | v1.0.4 | 网易云免加密接口 + LRC 同步高亮 |
| 9 | 纵向音量条（调节+显示一体） | v1.0.4 | 自定义 `VolumeBar` |
| 10 | 全屏→浮窗/分屏按窗口比例刷新重排 | v1.0.5 | `Win` 窗口度量 + 布局按宽高比显式切换 + 控件缩放 |
| 11 | 歌词搜索栏预填歌手+歌名（去编号） | v1.0.6 | `suggestQuery` 清洗管道 |
| 12 | 队列按钮放底部 | v1.0.6 | 移入播放页底部操作区 |
| 13 | 队列页按曲库样式+正在播放标记+同类操作 | v1.0.6 | `QueueActivity` 复用 `TrackAdapter` |
| 14 | 立即播放=入列并播放 | v1.0.6 | `insertAndPlay` |
| 15 | 曲库按筛选范围批量导入播放 | v1.0.6 | `replaceQueue`（不打断当前曲） |
| 16 | 迷你条播放事件提醒+标题动画 | v1.0.6 | 状态文案+进度线+过渡动画 |
| 17 | 曲目列表与播放页「返回 B 站」 | v1.0.7 | `TrackActions.openBili`（bvid+分P → ACTION_VIEW） |
| 18 | 自动开启缓存 | v1.0.8 | 起播即自动入下载队列（受仅WiFi约束） |
| 19 | 歌词搜索过滤标题 + 跳过取词失败候选 | v1.0.8 | 字符重合度过滤 + 逐候选重试取词 |
| 20 | 播放页布局重排（缓存/B站上移、底部三按钮、顶部曲库入口） | v1.0.8 | 竖/横两套布局同步调整 |
| 21 | 队列清空 + 多选批量删除 | v1.0.8 | 顶栏 ✓/🗑，checkbox 多选，clearQueue/removeIds |
| 22 | 最近与我的拆分 | v1.0.8 | 四 tab 导航 |
| 23 | 应用内更新（GitHub 直链） | v1.0.8 | DownloadManager + 安装器拉起 |
| 24 | 桌面 2×2 播放/暂停小组件（点空白进播放页） | v1.0.9 | `WidgetProvider` + ACT_STATE 同步图标；toggle 用 getForegroundService 绕过后台启动限制 |
| 25 | 扫码添加（摄像头扫码 + 相册图片识别，离线） | v1.0.9 | `util/QrDecoder` 纯 Java 解码（v1-10、RS纠错/BCH format/zigzag，12/12 测试通过）；`ui/ScanActivity` 相机逐帧解码 + `decodeImageUri` 四向旋转重试 |
| 26 | 粘贴链接快速添加 | v1.0.9 | 「+」来源菜单第一项直接读剪贴板 → `doImport` |
| 27 | 曲库批量：多选删除 / 一键清空 | v1.0.9 | 复用 `TrackAdapter.setSelectable` 多选；`Db.deleteTrack` 批删 + `removeIds`；`Db.clearTracks` + `clearQueue` |
| 28 | 检查更新（版本比对 + 介绍页） | v1.0.9 | 拉 `release/version.json` 比对 versionCode → `UpdateActivity` 介绍页 / 「已是最新」提示 |
| 29 | 离线播放：跳过未缓存曲目，整列表无缓存则停 | v1.0.10 | `PlaybackService.isOffline()`（ConnectivityManager）；startSource / onError / doPlay 三处前置离线检查 → `skipOffline()` 计数一轮（prepared 成功或 setQueue 归零） |
| 30 | 扫码方向/比例/识别率修复 | v1.0.10 | `setDisplayOrientation(传感器-屏幕旋转)` + 预览 SurfaceView 按 letterbox 调宽高比 + 解码帧按显示方向旋转（4 向轮试）；QrDecoder 增加自适应局部二值化（积分图窗口均值-15%）兜底 |
| 31 | 设置页介绍文字移至独立关于页 | v1.0.10 | `AboutActivity`：图标/版本/介绍/三种添加方式/开源地址（长按复制）；设置页改为一行入口 |
| 32 | 数据备份/恢复 + 打开缓存文件夹 | v1.0.10 | `util/Backup`：导出 JSON（曲目+歌单+历史）到 Download（API29+ MediaStore，≤28 直写+权限）；恢复为合并语义（已有不覆盖，恢复后视为未缓存）；`openFolder` ACTION_VIEW resource/folder → 兜底弹路径+复制 |
| 33 | 小组件两行歌曲信息自适应 | v1.0.10 | 播放钮缩至 62dp；歌名/歌手两行 `setTextViewTextSize` 按长度分档（15→9sp），保证 ≥8 字不省略 |
| 34 | 播放队列可删除曲目（删除后同时移出队列） | v1.0.11 | `QueueActivity` 行菜单「删除曲目」传入 after 回调：`Db.deleteTrack` 后 `PlaybackService.removeIds`，服务广播触发列表刷新（此前只删曲库、队列不变，看起来像没反应） |
| 35 | 曲库多选批量加入播放队列 / 加入歌单 | v1.0.11 | 选择栏加「加入播放队列」「加入歌单」文字按钮（选中才显示）；队列走新增 `A_APPEND_IDS`（批量 append，不打乱当前播放）；歌单 `Db.addTo` 逐条插入 + `reloadPlaylists()` 即时刷新计数 |
| 36 | 桌面小组件播放钮居中 | v1.0.11 | 根布局 `gravity` 由 `center_vertical` 改 `center`（垂直布局下才带水平居中），FrameLayout 加 `layout_gravity=center_horizontal` |
| 37 | 批量加入歌单也提供「新建歌单...」 | v1.0.12 | `TrackActions.pickPlaylist` 统一选择器（末项固定「新建歌单...」），单曲 `addToPlaylist` 与批量 `addSelectedToPlaylist` 共用 `PickListener` 回调，行为一致 |
| 38 | 歌单播放时高亮「正在播放」 | v1.0.12 | `PlaylistActivity.load()` 接 `ACT_STATE` 刷新 + `TrackAdapter.setPlaying(当前id, 是否播放)`；歌单列表 `p.id==playingPid` 显示「正在播放」并粉色高亮 |
| 39 | 返回 B 站跳过浏览器直达 App | v1.0.12 | `TrackActions.openBili` 先 `setPackage` 指定 B 站包名拉起 App Links，再退 `bilibili://` scheme，最后才退回系统选择器（不再先弹浏览器） |
| 40 | 返回 B 站分 P 偏移修正 | v1.0.12 | `Bili` 解析 `page` 为 1 起算；`openBili` 用 `?p=page` 原样（去掉误加的 +1），多 P 视频准确定位对应分 P |
| 41 | 删除曲目提示会同时删曲库/缓存 | v1.0.12 | `TrackActions.confirmDelete` / `QueueActivity` / `PlaylistActivity` 删除前弹「同时会从曲库删除，且本地缓存文件一并删除」 |
| 42 | 导入时推断歌名/歌手/专辑 | v1.0.12 | `util/MetaResolver`：`parseLocal` 本地规则即时推断 + `enqueue` 单线程联网（网易云）校准；`Db` 增 `song/artist/album/srcTitle` 列；曲库可按歌手/专辑筛选、搜索与歌词用规范名 |
| 43 | 勾选模式支持全选/反选 | v1.0.13 | 选择栏加「全选」「反选」按钮；`MainActivity.selectAllTracks()` / `invertSelectedTracks()` 基于 `trackAdapter.data()` 批量操作 `libSelected` 后 `setSelectable` 刷新 |
| 44 | 删除全部按钮改为删除所选（灰度/亮起） | v1.0.13 | 头部 `btn_lib_clear` 由「清空曲库」改为 `deleteSelectedTracks`；`updateLibSelCount()` 按 `libSelected` 是否为空 `setEnabled` + `setAlpha(0.35/1.0)`；移除选择栏冗余删除按钮 |
| 45 | 列表显示「歌手 - 歌名」+ 专辑小字 | v1.0.13 | `Track.listTitle()` = 歌手(空为 Unknown) + " - " + 歌名；`Track.listSub()` = 专辑(空为 Unknown)；`TrackAdapter` 列表行改用这两个方法（曲库/最近/歌单/队列一致） |

---

## 四、验证状态

- **全部需求**均已在真机（OPPO N6 Android 16 / Samsung Note8 Android 9）完成功能验证；
- v1.0.7「返回 B 站」在 Note8 上验证菜单项与跳转；v1.0.8 的八项（自动缓存/歌词过滤/布局重排/队列批量操作/四页导航/应用更新）在 Note8 验证，更新链路实测到系统安装器弹出；
- v1.0.9 桌面小组件已在 Note8 验证：添加到桌面、按钮播放/暂停切换（`dumpsys media_session` state 2↔3）、图标随 ACT_STATE 同步、点按钮外空间打开播放页且播放不间断；
- v1.0.9 其余各项 Note8 验证：相册识别二维码→导入成功（BV1xx411c7mD）、多选勾选（已选 1/3）→删除→曲目移除（3→2首）、清空确认框、扫码页+相机权限+实时预览、检查更新显示「已是最新 v1.0.9」（真机拉取线上 version.json）；
- v1.0.10 在 Note8 验证：关网后播放逐首跳过并提示、整列表无缓存试完一轮停止（toast「整个列表都没有缓存，已停止播放」）；扫码预览正立不变形、相册识别回归通过；设置页无介绍文字、关于页正常（v1.0.10）；备份导出 Download/bmusic_backup_*.json 成功、删除曲目后恢复合并导入成功（2→3 首）；打开缓存文件夹弹路径兜底；小组件显示小圆钮+两行完整文字；
- v1.0.11 在 Note8 验证：曲库多选 2 首→「加入播放队列」→播放页队列 1→3 首；队列页行菜单「删除曲目」→队列 3→2 首且曲库同步 2 首；多选 2 首→「加入歌单」→toast 已加入 2 首、歌单计数即时刷新为 2（重启前也一致，说明数据与 UI 同步）；小组件播放钮水平垂直居中；
- v1.0.12 在 Note8 验证：①批量勾选 2 首→「加入歌单」→选择器含「新建歌单...」→新建「Mix」后提示已加入 2 首、歌单计数刷新；②歌单被播放后列表显示「正在播放」、歌单内当前曲目粉色高亮（`R.id.now` 图标 + 标题变粉）；③返回 B 站直接拉起 B 站 App、定位正确视频且分 P 正确（不再经浏览器）；④删除曲目弹窗含「同时会从曲库删除，本地缓存也一并删除」；⑤导入后曲库按歌手/专辑筛选可用，元信息识别正确（如「A20 LITE 'NEON' / A2O · NEON」）；`MetaResolver` 本地解析 9 例单测通过，联网校准在 429 时安全降级（parseLocal 已足够）；
- v1.0.13 在 Note8 验证：①勾选模式点「全选」→「已选 249 / 249」全部打勾；再点「反选」→「已选 0 / 249」并保持在勾选模式；②未勾选时头部删除按钮置灰（alpha 0.35）且禁用，勾选后正常亮起；③列表主标题为「歌手 - 歌名」（如「梁静茹 - 从前的爱」），下方小字为专辑（如「燕尾蝶」），未解析到显示 `Unknown`；曲库/最近/歌单/队列共用 TrackAdapter 格式一致；
- 已知不修复项：`am start` 直拉非 exported Activity 被系统拒绝（设计使然，走应用内导航 / 通知栏）。

---

<p align="center"><sub>BMusic requirements · 更新于 v1.0.13</sub></p>
