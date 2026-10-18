# BMusic 技术文档

> 面向开发者的实现说明：B 站接口调用、wbi 签名、DASH 取流、后台播放服务、缓存策略，以及**无 Gradle 离线构建流水线**的完整细节。

---

## 1. 设计目标与约束

| 目标 | 约束 / 选择 |
| --- | --- |
| 无第三方依赖 | 只用 Android Framework + `org.json`，不用 Retrofit / OkHttp / Glide / ExoPlayer；仅二维码解码 vendored 了 ZXing 的 QR 子集（50 个文件，Apache 2.0） |
| 无 Gradle | 直接用 SDK 自带工具链脚本构建，离线可复现 |
| 极小体积 | APK ≈ 255 KB（对比常规 Gradle + 依赖的 App 动辄数 MB） |
| 老设备可用 | minSdk 24（Android 7.0），实测 Android 9（三星 Note8）与 Android 16（OPPO N6 近方屏）流畅运行 |
| 稳定取流 | 实现官方 **wbi 请求签名**，降低接口被拦截的概率 |

选择 `MediaPlayer` 而非 ExoPlayer，是为了**零依赖**——代价是需要自己处理 CDN 的 `Referer` 校验（见 §4.2）。

---

## 2. 整体架构

```
┌──────────────────────────────────────────────────────────┐
│  UI 层      MainActivity（5 tab + 内嵌 QueuePage）          │
│             PlayerActivity / PlaylistActivity /            │
│             SettingsActivity / ShareActivity /             │
│             ScanActivity / UpdateActivity / AboutActivity  │
│             TrackAdapter/PlaylistAdapter  MarqueeView      │
│             TrackActions（歌曲操作统一入口）                 │
├──────────────────────────────────────────────────────────┤
│  播放层     PlaybackService（前台服务 + MediaSession）      │
│             MediaButtonReceiver（耳机 / 蓝牙按键）          │
├──────────────────────────────────────────────────────────┤
│  业务层     Importer（导入编排）  Downloader（缓存下载）    │
│             MetaResolver（元信息推断/校准）                 │
├──────────────────────────────────────────────────────────┤
│  B站接口    Bili（解析/取流）  Wbi（签名）                  │
│  第三方     QQ 音乐公开搜索（补专辑）  网易云（歌词）        │
├──────────────────────────────────────────────────────────┤
│  网络层     Http（HttpURLConnection 封装 + 重定向跟随）     │
│  数据层     Db（SQLite）  Cache（文件）  CoverLoader（图片）│
│  扫码       QrDecoder → vendored ZXing QR 子集             │
└──────────────────────────────────────────────────────────┘
```

线程模型：**主线程只做 UI**，所有网络 / 磁盘 / 数据库操作走 `App.postBg()`（单线程池）或 `App.postNet()`；结果通过 `App.runOnUi()` 回主线程，或用本地广播通知界面刷新。

---

## 3. 包结构与类职责

```
com.volcano.bmusic
├── App.java                  全局 Application：线程池、SharedPreferences、广播回调
├── model/
│   ├── Track.java            曲目实体（bvid/cid/page/title/song/artist/album/srcTitle/cached…）
│   └── Playlist.java         歌单实体
├── net/
│   └── Http.java             HttpURLConnection 封装：GET/POST、重定向跟随、下载、公共头
├── api/
│   ├── Wbi.java              wbi 签名（mixin key 混淆表 + MD5）
│   ├── Bili.java             链接解析 / view 取信息 / playurl 取音频流
│   └── Importer.java         导入编排：文本 → 解析 → 入库 → 元信息推断 → 通知界面
├── db/
│   └── Db.java               SQLiteOpenHelper + 全部 DAO
├── player/
│   ├── PlaybackService.java  播放引擎（前台服务 / 队列 / 模式 / 通知 / AudioFocus）
│   └── MediaButtonReceiver.java  媒体按键转发
├── lyric/
│   ├── NeteaseLyric.java     网易云免加密接口搜索/取词 + 查询词清洗
│   └── Lyric.java            LRC 解析
├── util/
│   ├── Cache.java            缓存目录与文件命名
│   ├── CoverLoader.java      封面：LruCache + 磁盘 + 手写 stack blur
│   ├── Downloader.java       音频缓存下载 + 进度广播
│   ├── MetaResolver.java     标题→歌名/歌手/专辑：本地规则即时推断 + QQ 音乐联网补专辑
│   ├── QrDecoder.java        QR 解码薄壳（灰度 int[] → vendored ZXing）
│   ├── Backup.java           曲库/歌单/历史 JSON 导出与合并恢复
│   ├── ApkUpdater.java       应用内更新（version.json 比对 + DownloadManager 下载）
│   ├── Receivers.java        广播注册统一封装（Android 13+ RECEIVER_NOT_EXPORTED）
│   ├── Win.java              多窗口/折叠屏窗口度量（WindowMetrics）
│   └── Notify.java           通知渠道
└── ui/
    ├── MainActivity.java     单 Activity 承载 5 tab（含内嵌 QueuePage）
    ├── QueuePage.java        播放列表页嵌入式控制器（宿主为主界面）
    ├── PlayerActivity.java   播放详情页（竖/横双布局，按窗口宽高比显式选择）
    ├── PlaylistActivity.java 歌单详情页
    ├── SettingsActivity.java 设置页
    ├── ShareActivity.java    分享 / 链接接收（透明主题对话框）
    ├── ScanActivity.java     扫码添加（相机逐帧 + 相册识别）
    ├── UpdateActivity.java   应用更新介绍页
    ├── AboutActivity.java    关于页
    ├── TrackActions.java     歌曲操作统一入口（四列表 + 播放页共用）
    ├── MarqueeView.java      往复跑马灯容器（HorizontalScrollView 方案）
    ├── VolumeBar.java        纵向音量条（调节+显示一体）
    ├── WidgetProvider.java   2×2 桌面小组件
    └── adapter/              TrackAdapter / PlaylistAdapter
```

---

## 4. B 站接口层

### 4.1 会话初始化：Cookie 与风控

首次请求前 `Bili.ensureSession()` 会做两件事：

1. **拿游客 Cookie**：访问 `www.bilibili.com` 首页，必要时再请求 `x/frontend/finger/spi` 接口，把返回的 `buvid3 / buvid4 / b_nut` 写进 `CookieManager`。这些是 B 站对游客的"指纹"标识，缺少时容易触发风控。
2. **拿 wbi 密钥素材**：请求 `x/web-interface/nav`，从 `data.wbi_img.img_url` 和 `sub_url` 中截取文件名（不含扩展名），得到 `imgKey` 与 `subKey`。

密钥 4 小时视为过期（`Wbi.stale()`），过期后自动重新拉取；接口返回 `-403` 时也会主动清空密钥强制刷新。

### 4.2 wbi 请求签名（`Wbi.java`）

B 站对部分接口要求带 `w_rid` 签名，算法如下：

```text
1. 把 imgKey + subKey 拼成 64 位 rawKey
2. 用固定混淆表 TABLE[32] 取值，得到 32 位 mixinKey
3. 参数按 key 升序排序，过滤掉 ! ' ( ) * 五个字符，URL 编码
4. w_rid = MD5( 排序后的 query + mixinKey )
5. 最终 query 追加 &w_rid=xxx   （wts 为秒级时间戳）
```

核心代码：

```java
private static final int[] TABLE = {46,47,18,2,53,8,23,32, /* …共 32 个… */ };

private String mixinKey() {
    StringBuilder sb = new StringBuilder(32);
    for (int i = 0; i < 32; i++) sb.append(rawKey.charAt(TABLE[i]));
    return sb.toString();
}

public String sign(Map<String,String> src, long nowSec) {
    Map<String,String> params = new TreeMap<>(src);
    params.put("wts", String.valueOf(nowSec));
    // …按 key 排序、过滤特殊字符、URL 编码…
    String wrid = md5(base + mixinKey());
    return base + "&w_rid=" + wrid;
}
```

`Bili.call()` 会**优先带签名请求**；若失败（如 `-403`）则清空密钥并回退到未签名请求，保证在网络策略变化时仍有可用路径。

### 4.3 链接解析（`Bili.resolve`）

输入可能是任意一段分享文案，解析分三步：

1. **正则直取**：先用 `BV[0-9A-Za-z]{10}`、`av(\d+)` 从文本里直接抓 ID；顺带解析 `?p=3` 这样的分 P 参数。
2. **短链跟随**：若只有 URL，则用 `Http.follow()` 跟随重定向（最多 6 跳），每跳都尝试从 `Location` 或响应体里正则抓 `BV/av`。`b23.tv` 短链就是靠这一步还原的。
3. **兜底报错**：都失败则抛出可读的 `ApiException`。

### 4.4 视频信息（`view` 接口）

`Bili.fetch(Ref)` 请求 `x/web-interface/view`，取出标题、UP 主、封面、总时长。多 P 视频遍历 `data.pages`，按 `p` 定位对应分 P 的 **cid**（取流的必需参数），并用分 P 名作为标题。

曲目主键设计为 `bvid + "_p" + page`，于是同一个视频的不同分 P 是**独立曲目**。

### 4.5 音频流（`playurl` 接口）

`Bili.audioUrl(Track)` 请求 `x/player/playurl`，参数 `fnval=16` 表示支持 DASH：

```java
JSONObject dash = data.optJSONObject("dash");
JSONArray arr = dash.optJSONArray("audio");
// 优先级：30280(192K) > 30232(132K) > 30216(64K)
// 跳过 30250（杜比全景声），拿到 base_url
```

取到的是 CDN 上的 `.m4s` 音频文件地址。若没有 DASH（老视频），回退到 `durl[0].url`。

> **关键点**：请求 CDN 上的媒体文件**必须带 `Referer: https://www.bilibili.com/video/<bvid>` 和 UA**，否则返回 403。这是 `MediaPlayer` 必须 `setDataSource(context, uri, headers)` 而非普通 `setDataSource(url)` 的原因。

---

## 5. 播放引擎（`PlaybackService`）

### 5.1 生命周期与前台服务

- 通过 `startForegroundService` 启动，`onCreate` 里立即 `startForeground` 建立通知，避免 `ForegroundServiceDidNotStartInTimeException`
- `onDestroy` 释放 `MediaPlayer`、注销广播、`abandonAudioFocus`
- Android 12+ 声明 `foregroundServiceType="mediaPlayback"`

### 5.2 播放与音源选择

```java
private void startTrack(Track t) {
    // 本地优先：缓存存在则直接读文件
    if (t.hasLocal() && new File(t.path).exists()) {
        mp.setDataSource(t.path);
    } else {
        String url = Bili.audioUrl(t);       // 带 wbi 签名取流
        Map<String,String> h = Bili.mediaHeaders(Bili.refererOf(t)); // Referer + UA + Cookie
        mp.setDataSource(this, Uri.parse(url), h);   // ← 带头的关键
    }
    mp.prepareAsync();
}
```

失败时按次数退避重试，并在通知里给出错误提示。

### 5.3 控制与通知

- `MediaSession` 绑定 `PlaybackState`（播放 / 暂停 / 缓冲）与 `MediaMetadata`（标题 / 歌手 / 大图）
- 通知使用 `Notification.MediaStyle`，动作：上一首 / 暂停 / 下一首；小图标是自绘的音频音符 PNG
- `AudioManager.requestAudioFocus()` 处理来电等打断；监听 `ACTION_AUDIO_BECOMING_NOISY`（拔耳机自动暂停）
- `MediaButtonReceiver` 接收耳机线控 / 蓝牙的 `MEDIA_BUTTON`

### 5.4 队列与播放模式

- `queueIds` 保存当前队列的曲目 id 列表，`index` 指向当前曲
- 模式：`MODE_LOOP`（列表循环）/ `MODE_SINGLE`（单曲循环）/ `MODE_SHUFFLE`（随机）
- 模式持久化到 `SharedPreferences("mode")`
- 队列操作（`onStartCommand` 按 action 分发）：

| action | 语义 |
| --- | --- |
| `A_QUEUE` | 整体设置队列并播放指定曲目（歌单播放 / 播放全部） |
| `A_PLAY_NOW` | 「立即播放」：不在队列则插入当前曲之后，随即播放 |
| `A_QUEUE_KEEP` | 替换队列内容但**不打断**正在播放的曲目（曲库批量导入） |
| `A_QUEUE_EDIT` | 队列编辑：移除 / 上移 / 下移 |
| `A_APPEND` / `A_APPEND_IDS` | 追加单首 / 批量追加到队列尾部（不打乱当前播放） |
| `A_QUEUE_CLEAR` / `A_QUEUE_RM_IDS` | 清空队列（并暂停）/ 批量移除指定曲目 |

- **冷启动与服务唤醒**：UI（MainActivity / PlayerActivity / QueuePage）可能先于服务跑起来
  （进程被系统回收或重装后），此时 `PlaybackService.get()` 为 null，界面展示的是
  SharedPreferences 里的持久化状态。三处 UI 在服务缺失时调 `PlaybackService.wake()`
  （无 action 的 `startForegroundService`，`onCreate` 会 `restore()` 出上次的队列），
  避免出现「播放页显示 132 首、队列页却是空的」这种状态不一致。

### 5.5 界面刷新

Service 通过本地广播与 UI 通信（不依赖 `bindService`）：

| 广播 | 含义 |
| --- | --- |
| `PlaybackService.ACT_STATE` | 状态变化（播放 / 暂停 / 换曲 / 报错） |
| `PlaybackService.ACT_PROGRESS` | 进度更新（驱动进度条） |
| `Downloader.ACT_DL` | 缓存进度 / 完成 / 失败 |
| `CoverLoader.ACT_COVER` | 封面下载完成 |

---

## 6. 数据层（`Db`）

### 6.1 表结构

```sql
tracks(   id TEXT PK, bvid, cid, page, title, author, cover, duration,
          added, last_played, plays, cached, path, fav,
          song, artist, album, srcTitle )                         -- 曲库（VER=2 增后四列）
playlists(id INTEGER PK AUTOINCREMENT, name, created, kind)        -- 歌单(kind=1 为内置)
pl_tracks(pid, tid, ord, PRIMARY KEY(pid,tid))                     -- 歌单↔曲目，ord 排序
history(  tid, played )                                            -- 播放历史
```

- 首次建库自动插入一条 `kind=1` 的「我喜欢的音乐」
- 播放历史只保留最近 **300** 条（`DELETE … WHERE rowid NOT IN (SELECT … LIMIT 300)`）
- `upsert` 用 `CONFLICT_REPLACE`，重复导入同一 BV 不会产生重复曲目
- 移除歌单曲目用 `CONFLICT_IGNORE`，天然去重
- `song / artist / album / srcTitle` 为 v1.0.12 引入的推断元信息列（见 §9），
  `onUpgrade` 走重建迁移

### 6.2 主要 DAO

`allTracks / get / recent / tracksOf / upsert / setCache / touchPlay / playlists / favId / addTo / removeFrom / reorder / clearPlaylist / deleteTrack`

`reorder` 用事务批量更新 `ord`，保证拖动排序的一致性。

---

## 7. 缓存与资源

### 7.1 目录布局

```
/sdcard/Android/data/com.volcano.bmusic/files/bmusic/
├── audio/<hash>.m4a     音频缓存
└── cover/<hash>.jpg     封面缓存
```

文件名由 URL 的 hash 生成，避免非法字符与过长路径。

### 7.2 下载（`Downloader`）

- 复用 `Http.download()` 流式写盘，边下边通过 `ACT_DL` 广播进度（供列表显示百分比）
- 完成后写 `Db.setCache(id, true, path)`，曲目列表显示绿色"已缓存"角标
- 「自动缓存下一首」在切歌时触发；可开「仅 WiFi 缓存」（读 `ConnectivityManager` 判断网络类型）

### 7.3 封面（`CoverLoader`）

- **三级缓存**：`LruCache`（16 MB）→ 磁盘 → 网络
- 解码时按 `maxSide` 做二次采样（`inSampleSize`）+ `RGB_565`，省内存
- **播放页模糊背景**：把封面缩到 1/8，再跑一个**纯 Java 的堆栈式模糊**（`fastBlur`），不依赖 RenderScript，任何 Android 版本都能用
- 用 `ImageView.setTag(url)` 做"绑定校验"，避免列表滚动时图片错位

---

## 8. UI 层

### 8.1 单 Activity 承载五页

`MainActivity` 用一个 `FrameLayout` 叠放「曲库 / 歌单 / 播放列表 / 最近 / 我的」五页，底部自绘导航切换 `visibility`。好处是切换无跳转开销、迷你播放条与导航栏常驻。播放列表页由 `QueuePage` 嵌入式控制器承载（构造时传宿主 Activity，宿主把状态/下载广播分发给它），不再是独立 Activity。

### 8.2 ⚠️ 一个重要陷阱：列表项点击失效

**现象**：曲库列表点条目无反应，`ListView.setOnItemClickListener` 完全不被调用。

**原因**：列表项右侧放了一个「更多」`ImageButton`（`⋮`）。`ImageButton` 默认 `clickable=true`（可点击的**后代 View**），在某些 Android 版本上会让 `ListView` 判定整行"已被子 View 消费"，于是 `OnItemClickListener` 静默失效。

**解决方案**：不再依赖 `ListView` 的 item click，而是在 **Adapter 的 `getView()` 里直接给整行绑定点击**：

```java
// TrackAdapter.getView()
v.setOnClickListener(new View.OnClickListener() {
    @Override public void onClick(View v) {
        if (listener != null) listener.onPlay(data, position);
    }
});
// 「更多」按钮保留自己的 OnClickListener，点击按钮时事件被按钮消费
```

`Listener` 接口相应地扩展为：

```java
interface Listener {
    void onPlay(List<Track> list, int pos);   // 整行点击
    void onMore(View anchor, Track t);        // 更多按钮
}
```

这样"点整行播放"和"点按钮弹菜单"互不干扰，也彻底绕开了 `ListView` 子 View 焦点/点击的坑。三处调用方（曲库、最近、歌单详情）统一实现 `onPlay`。

### 8.3 往复跑马灯（`MarqueeView`）—— 两次踩坑后的最终方案

超长文字不出现「…」而是缓慢往复滚动。第一版（v1.0.17）在 TextView 上自研 scrollX，第二版（v1.0.20）双视图切换，都在三星设备上撞了渲染路径的坑（`canvas.translate` / 程序化 padding 居中会让**整段文字不绘制**；`animator_duration_scale=0` 的设备上 ValueAnimator 瞬间播完）。v1.0.21 重写为开源社区验证过的 **HorizontalScrollView 方案**：

- **结构**：HSV 内包一个 wrap_content 的 TextView（XML 里保留原功能 id，代码零改动）；
- **短文字**：内容比视口窄 → 子视图 `Gravity.CENTER` 居中（⚠️ 只设 `CENTER_VERTICAL` 水平会靠左，FrameLayout 默认水平 start）；
- **长文字**：`Handler` + `SystemClock` 按**真实时间**驱动 `HSV.scrollTo`，往复「向左滚出右边 → 停 → 向右滚回左边 → 停」（≈30px/s，启动静置 2.5s、两端停 1.5s）。滚动发生在滚动容器上而非 TextView 内部渲染路径，全机型一致；
- **滚动终点**：`maxScroll = t.getWidth() - avail`——`TextView.getWidth()` **已含自身 padding**，再叠加会导致到头多滚一段；
- **文字变化感知**：给子 TextView 加 `TextWatcher`，自动重新评估（布局尺寸变化走 `onLayout(changed)`）；
- **列表高频 bind**：`runningText` 守卫，同一文字重复绑定不重启动画；
- **⚠️ 必须重写 `onTouchEvent` 返回 false**：HSV 默认消费整个触摸序列，包在列表行里会**吞掉行点击/长按**、迷你条标题区点击也失效（v1.0.22 修复）。播放页按钮的点击在内层 `clickable=true` 的 TextView 上，不受影响。

使用位置：列表行 title/sub、播放页标题/作者/六个按钮（曲库入口/缓存/返回B站/播放队列/加入歌单/歌词）。

废弃的 `MarqueeTextView`（v1.0.17 自研方案）已删除，其教训保留在需求文档 #51/#53。

### 8.4 分享接收（`ShareActivity`）

- 透明主题 `Theme.Transparent` + `noHistory`，像一个弹出对话框
- 接收 `ACTION_SEND`（`text/plain`）与 `ACTION_VIEW`（`bilibili.com/video/*`、`b23.tv/*`）
- 从中提取链接后弹出「立即播放 / 添加到曲库 / 下一首播放」选项
- `MainActivity` 自身也注册了 `VIEW` 过滤器，从浏览器直接打开链接也能导入

### 8.5 迷你播放条

底部常驻，展示当前曲的封面 / 标题 / 作者与播放暂停、下一首按钮；点击进入播放页。刷新由 `ACT_STATE` / `ACT_COVER` 广播驱动。

- 状态提示：依据播放器实时状态显示「缓冲中… / 播放中 / 已暂停」，并配一根粉色进度线
- 标题 / 副标题切换时做淡入淡出 + 位移过渡（`animateText()`），播放键切换有脉冲缩放
- 「立即播放 / 播放 N 首 / 加入队列」等队列事件都会立刻反映到迷你条上

### 8.6 播放列表页（`QueuePage`）与歌曲操作（`TrackActions`）

- 播放列表不再是独立 Activity：`QueuePage` 作为嵌入式控制器被主界面 `<include>` 进页面栈，
  复用曲库的 `item_track` 行布局与 `TrackAdapter`，当前曲目标记 ▶ 并高亮
- 行点击 = 立即播放；`⋮` 弹出操作菜单（立即播放 / 上移 / 下移 / 缓存 / 加入歌单 / 从队列移除 / 删除曲目）
- 各页面（曲库 / 最近 / 歌单详情 / 播放列表）的歌曲操作菜单收敛到 `TrackActions`
  （`playNow / cache / addToPlaylist / confirmDelete`），避免同一逻辑四份实现；
  菜单标题用自定义视图 `view_menu_title`（`maxLines=3` + 主题粉加粗 16sp，与动作项用颜色区分）
- 歌词搜索预填由 `NeteaseLyric.suggestQuery(title, author)` 生成：去掉 `P1 / 第N集 / BV号` 等编号噪声，
  「`<序号><歌手> - <歌名>`」类标题拆成「歌手 歌名」，不含编号与 UP 主名
- 播放页底部「播放队列」按钮 → `page=2` extra + `CLEAR_TOP|SINGLE_TOP` 复用主界面实例并自动切 tab

### 8.7 分屏 / 浮窗（自由窗口）适配

多窗口下窗口尺寸可随时变化，且**不再等于物理屏幕**，因此布局不能依赖整机方向。

**(1) 不重建 Activity。** 四个 Activity 都声明：

```xml
android:resizeableActivity="true"
android:configChanges="orientation|screenSize|smallestScreenSize|screenLayout|density|keyboardHidden|fontScale|uiMode"
```

关键点是 **`smallestScreenSize`**：进入分屏 / 拖动浮窗时它会变化，若未声明，系统会**销毁并重建** Activity——播放页的歌词面板状态、每次拖动都重建的抖动都由此而来。声明后改由应用自己处理。

**(2) 以窗口为单位度量（`util/Win`）。**

```java
Win.sizeDp(activity)   // API 30+ 用 WindowMetrics#getCurrentWindowMetrics；
                       // API 24+ 回退 Configuration.screenWidthDp/HeightDp（多窗口下即为窗口值）
Win.wide(activity)     // 窗口宽 > 高
```

`getDefaultDisplay()` 在多窗口下返回的是**屏幕**尺寸而非窗口尺寸，不能用于这里的判断。

**(3) 播放页按窗口比例重排（`PlayerActivity`）。**

- 布局不再依赖 `-land` 资源限定符：横屏版改名为 `res/layout/activity_player_land.xml`，由代码按 `Win.wide()` **显式选择**，窗口宽高比变化时 `setContentView` 换布局并重新 `bind()/actions()`（歌词面板的开合状态用 `lyricOpen` 记录后恢复）。
- 回调入口：`onConfigurationChanged` 与 `onMultiWindowModeChanged` 都走同一个 `relayoutForWindow()`。
- `applyWindowSize()` 按窗口 dp 缩放内容：封面取 `min(w×0.62, h×0.30)`（钳制 96–260dp）、竖向音量条高度取 `min(190, h×0.30)`、播放键 72→56dp、其余按键 52/48→44/40dp；`h<660dp` 为紧凑模式，`h<470dp` 为极窄模式（隐藏状态行、压缩行距与内边距）。
- 底部三个操作按钮（缓存 / 加入歌单 / 歌词）先按常规排版，再在 `post()` 里用 `Layout.getEllipsisCount(0)` 检查是否被截断，若被截断则去掉前置图标并缩小字号后重排——**基于实测宽度而非拍脑袋阈值**，任意窗口尺寸都成立。

**(4) 主界面（`MainActivity`）** 只做重量级元素的自适应：底栏 54→44dp、迷你条 60→52dp、"最近播放"列表高度 `h×0.34`（钳制 110–240dp）。

---

## 9. 元信息推断（`MetaResolver`）与扫码（`QrDecoder`）

### 9.1 从标题推断「歌名 / 歌手 / 专辑」

B 站音频的原始标题常是「`0095. 路 - 梁静茹`」「`【官方 MV】Never Gonna Give You Up - Rick Astley`」这类形态，需要拆成规范的「歌名 / 歌手 / 专辑」：

- **本地规则即时推断（`parseLocal`）**：剥序号 → 按 UP 主名（`cleanAuthor`）出现在左/右 + 是否带序号，判定「歌名 - 歌手」的左右（合集「序号. 歌名 - 歌手」整条易被颠倒，是踩过的坑）；`《》`内优先作歌名；`A _ B` 多歌手统一为 `A / B`；
- **联网只补专辑、绝不覆盖歌名/歌手**：调 **QQ 音乐公开搜索**（`c.y.qq.com/soso/fcgi-bin/client_search_cp`，免签名，带 Referer）取真实发行专辑，采纳门控 = 候选歌名高度一致 + 歌手对得上，否则留空显示 `Unknown`。网易云源已弃用（翻唱多、专辑常是合集名）；百度百科有 JS 反爬（PARIS_CHALLENGE），纯 HTTP 抓不到；
- **存量重推（backfill）**：升级后首次启动对全部存量曲目按新规则重推（`meta_backfill_v5`：先清空旧专辑再重取），节流 400ms、连续 3 次失败即停防限流。

推断结果存 `tracks` 表的 `song / artist / album / srcTitle` 列（§6.1），列表显示与歌词搜索都优先用规范名。

### 9.2 二维码扫码

- **vendored ZXing 3.3.3 core 的 QR 解码子集**（Apache 2.0）：50 个文件只含解码所需类（无 encoder、无其他码制），兼容本项目「无 lambda、javac -source 8」约束；
- `QrDecoder` 是薄壳，签名不变（灰度 `int[]` → 自定义 `GraySource`），按 **全局直方图 → Hybrid 局部自适应 → 各自反色** 四路尝试，白底黑码/深底白码都能识别；
- `ScanActivity` 支持摄像头逐帧解码（预览按 letterbox 校正宽高比、解码帧按显示方向 4 向轮试）与相册图片识别；
- 测试注意：`segno` 生成短文本二维码会自动降级为 Micro QR（ZXing 子集不支持），测试须 `version=2` 强制标准码。

---

## 10. 无 Gradle 构建流水线（`build.sh`）

`aapt2 → javac → d8 → 打 dex → zipalign → apksigner`，七步走：

| 步骤 | 工具 | 说明 |
| --- | --- | --- |
| 1 | `aapt2 compile` | 编译 `res/` 为 `.flat` |
| 2 | `aapt2 link` | 链接资源 + manifest，生成含资源的 `app.apk`，同时产出 `R.java` |
| 3 | `javac` | 用 **JDK 17** 的 javac，`-source/-target 8`，`-bootclasspath android.jar` |
| 4 | `d8` | `java -cp d8.jar com.android.tools.r8.D8` 把 class 转 dex |
| 5 | Python zipfile | 把 `classes.dex` 塞进 apk |
| 6 | `zipalign` | 4 字节对齐 |
| 7 | `apksigner` | v1 + v2 签名（首次自动 `keytool` 生成 `debug.keystore`） |

### 踩坑记录

- **必须 `-bootclasspath android.jar`**，否则用系统 JDK 的 `java.*` 会缺 `android.*` 符号
- **`d8` 与 `apksigner` 需用 `java -cp <jar> <MainClass>` 调用**：直接 `-jar` 会报找不到主类
  - d8 主类：`com.android.tools.r8.D8`
  - apksigner 主类：`com.android.apksigner.ApkSignerTool`（**不是** `ApkSignerTool` 之外的其它拼写）
- **MSYS 路径坑（Git Bash on Windows）**：`pwd` 得到的是 `/c/...` 形式，Windows 工具读不了。必须用 `pwd -W` 拿 `C:/...` 真实路径，并设 `export MSYS2_ARG_CONV_EXCL="*"` / `MSYS_NO_PATHCONV=1` 防止参数被改写
- **heredoc 含中文/引号** 易被 shell 打断，配置与布局文件一律用文件写入工具而非 `cat <<EOF`

---

## 11. 真机调试记录（Samsung Note8 / Android 9 · OPPO N6 / Android 16）

| 场景 | 做法 |
| --- | --- |
| 静音测试 | `adb shell media volume --set 0 --stream 3`，全程音量 0，不扰民 |
| 定位控件坐标 | **screencap 全尺寸 + PIL 裁剪原比例区域读坐标**（缩略图心算缩放比易错）；⚠️ 有跑马灯动画的页面 `uiautomator dump` 永不满闲直接失败 |
| 截图 | Note8：`shell screencap -p /sdcard/s.png` + `adb pull`（exec-out 流易截断）；N6：输出带前缀，按 `\x89PNG` 签名切片 |
| 只看本 App 日志 | 自定义 tag `BMusic`，`adb logcat -s BMusic:V` |
| 查历史崩溃 | `adb shell dumpsys dropbox --print \| grep -A40 data_app_crash` |
| USB 频繁掉线 | `adb reconnect offline` 循环恢复；不行就重插（需重新授权） |
| 模拟分享 | `adb shell am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "<链接>" -n com.volcano.bmusic/.ui.ShareActivity` |
| 未导出页面 | `SettingsActivity` 等 `exported=false`，`am start` 会被系统拒绝，只能从 App 内导航进入（这是**正确**的安全行为） |

> 设备分辨率**随时可能漂移**：Note8 物理档 1440×2960（当前工作 override 1080×2220@480dpi），排查点击问题前先 `wm size / wm density` 确认，用完记得还原。`MSYS_NO_PATHCONV=1` 必须在所有 adb shell/pull 之前 export，否则 `/sdcard` 会被 Git Bash 改写。

---

## 12. 目录结构

```
bmusic-clone/
├── AndroidManifest.xml
├── build.sh                  # 无 Gradle 构建脚本
├── README.md                 # 产品说明
├── docs/
│   ├── TECHNICAL.md          # 本文档
│   ├── requirements/         # 需求全集与实现对照
│   └── screenshots/          # 真机截图
├── release/                  # 可直接下载安装的 APK + version.json
├── res/                      # 布局 / drawable / 图标 / values
└── src/
    ├── com/volcano/bmusic/   # 38 个自研 Java 文件，约 10200 行
    └── com/google/zxing/     # vendored ZXing QR 解码子集（50 文件，Apache 2.0）
```

---

## 13. 可改进方向

- 用 `SQLite` 之外的持久化播放队列（跨进程恢复更稳）
- `MediaSession` 兼容 Android 13+ 的媒体控制中心（`PlaybackState` 动作位更完整）
- 音质选择 UI（让用户显式指定 192K / 132K / 64K）
- 后台任务的 WorkManager 化，改善 Android 12+ 的省电约束
- 二维码扫码换 camera2/CameraX 预览管线（当前仍用已废弃的 Camera API，能跑但非长远之计）

---

<p align="center"><sub>BMusic 技术文档 · v1.0.22</sub></p>
