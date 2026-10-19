# LocalEmby（本地影院）

把手机里的本地视频目录，变成一个**本机可连的 Emby 服务器**。装好之后，任何 Emby 客户端（Hills、YAmby、Infuse 等）添加 `http://127.0.0.1:8096` 就能像浏览真 Emby 一样浏览、播放本地视频，并且**播放进度会记忆、可续播**，还支持**自动匹配弹幕**。

> 本项目是为了解决一个具体问题：想在 Emby 客户端里看手机本地视频，又不想为此专门跑一台 NAS / Jellyfin。它在手机本地模拟 Emby Server 的 HTTP API，零依赖、零转码，全部直连播放。

## 功能

- **HyperOS 风格界面**：基于 [Miuix](https://github.com/compose-miuix-ui/miuix)（Compose Multiplatform UI 库）重写，卡片式布局、系统级观感

- **本地目录即媒体库**：用系统文件夹选择器（SAF）授权目录，或用 MediaStore 扫全机视频
- **Emby API 模拟**：实现登录、媒体库、条目列表/详情、PlaybackInfo、图片、视频流（HTTP Range 拖动）、播放进度上报等接口
- **续播与进度记忆**：通过 Emby 的 `PlaybackPositionTicks` 协议上报，客户端原生「继续观看」生效；多个客户端共享同一份进度
- **自动弹幕**：客户端请求 `/api/danmu/{itemId}` 时，按文件名匹配弹弹play 兼容弹幕源并返回弹幕数据
- **刮削元数据**：支持通过 `/api/dev/metadata` 注入标题、简介、年份、评分、演职人员、海报
- **后台常驻**：前台服务 + WakeLock + 开机自启（仅在你主动开启过服务器时恢复）
- **请求日志**：App 内可查看全部请求，便于排查客户端兼容问题

## 目录结构

```
app/
  src/
    AndroidManifest.xml
    com/local/embylite/
      EmbyHttpServer.java   # 原生 HTTP 服务器：路由、Range 流媒体、弹幕/元数据接口
      EmbyApi.java          # Emby 协议层：System/Users/Items/PlaybackInfo/Sessions 等
      VideoLibrary.java     # 媒体库扫描（SAF / MediaStore / 目录遍历）
      Item.java             # 条目模型 + 文件名剧集解析（SxxExx、第N集、EPxx）
      ProgressDb.java       # 播放进度存储（Emby ticks）
      MetadataStore.java    # 刮削元数据覆盖层
      DanmuProxy.java       # 弹幕源代理（match + comment）
      ThumbProvider.java    # 视频抽帧缩略图 / 占位图
      HttpService.java      # 前台服务
      MainActivity.kt       # 主界面（Miuix/Compose）：启停、地址复制、目录选择、端口、弹幕源
      LogActivity.java      # 请求日志
      Settings.java         # 偏好设置
      BootReceiver.java     # 开机恢复
    res/                    # 布局与资源
  assets/web/index.html     # 浏览器调试页（列表 + 播放 + 进度上报）
```

## 构建

需要 JDK 17 与 Android SDK（compileSdk 36 / build-tools 36.0.0）。

```bash
# 1. 配置 SDK 路径
echo "sdk.dir=/path/to/android-sdk" > local.properties

# 2. 打包
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

签名默认使用仓库根目录的 `debug.keystore`（`signingConfigs.local`），正式发布请替换为你自己的密钥。

技术栈：AGP 8.13.2 · Kotlin 2.3.20 · Compose Multiplatform 1.10.3 · Miuix 0.8.8 · minSdk 26 / targetSdk 36。

## 使用

1. 安装 APK，打开「本地影院」
2. 点「选择文件夹」授权视频目录（或用输入框手填路径，支持 `/sdcard/xxx` 写法）
3. 点「启动服务器」，界面会显示 `http://127.0.0.1:8096`（点一下即可复制）
4. 在 Emby 客户端里添加服务器：地址 `http://127.0.0.1:8096`，用户名密码随意填
5. 浏览播放即可，进度自动记忆

局域网内其他设备也可以访问 `http://<手机IP>:8096`（同 WiFi）。

## 弹幕

客户端会向服务器请求 `/api/danmu/{itemId}?option=GetJsonById`，服务端按文件名（规范化为 `剧名 S01E01.mkv`）向弹弹play 兼容的弹幕 API 发起匹配：

```
POST {base}/api/v2/match          {"fileName":"..."}  → matches[0].episodeId
GET  {base}/api/v2/comment/{id}  ?withRelated=true    → {count, comments:[{p,m}]}
```

弹幕源地址在 `Settings.getDanmuApi()` 中配置（默认值见 `DanmuProxy.DEFAULT_API`，可自行替换为你部署的弹弹play 兼容服务）。

> 弹幕内容来自第三方服务，本项目不存储、不提供任何弹幕内容。

## 已实现的主要接口

| 接口 | 说明 |
|---|---|
| `GET /System/Info[/Public]` | 服务器信息 |
| `POST /Users/AuthenticateByName` | 登录（任意账号密码），返回 `AuthenticationResult` |
| `GET /Users/{uid}/Views` | 媒体库视图 |
| `GET /Users/{uid}/Items` | 条目列表（分页、排序、搜索、ParentId 过滤） |
| `GET /Users/{uid}/Items/Latest`、`/Items/Resume` | 最新 / 继续观看 |
| `GET /Users/{uid}/Items/{id}` | 条目详情 |
| `POST /Items/{id}/PlaybackInfo` | 播放信息（直连播放） |
| `GET /Videos/{id}/stream` | 视频流，支持 HTTP Range |
| `GET /Items/{id}/Images/Primary` | 缩略图 / 刮削海报 |
| `POST /Sessions/Playing[/Progress|/Stopped]` | 播放进度上报 |
| `GET /api/danmu/{id}` | 弹幕代理 |
| `POST /api/dev/metadata` | 注入刮削元数据 |
| `GET /api/dev/log` | 请求日志 |

## 已知限制

- **不做转码**：只提供直连播放，客户端需自行支持文件编码（Hills / YAmby 内置 mpv，支持良好）
- **无用户体系**：任意账号密码都能登录，仅建议本机或可信局域网使用
- **无 WebSocket**：部分客户端的实时会话推送不可用，不影响播放
- **APK 体积约 7MB**：Compose 运行时占用较大
- **`MediaStreams` 为占位信息**：部分客户端会显示固定的 1080p H.264 / AAC，实际轨道由播放器自行探测
- 小米等 ROM 对后台限制较严，若服务器被杀，可在系统设置里允许自启动并锁定后台

## 免责声明

本项目仅用于**管理和播放你自己拥有的本地媒体文件**，不提供、不索引、不托管任何影视内容。刮削与弹幕数据来自第三方公开服务，请遵守相应服务条款与当地法律法规。
