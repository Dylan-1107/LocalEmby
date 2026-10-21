# Embylite 交接文档（Handoff）

> 这份文档面向**接手本仓库的人**：项目概况、环境搭建、构建运行、目录结构、当前版本状态、已知问题与待办。
> 换电脑 / 工作区迁移后**先读这份**，能省掉半天时间。最后更新：2026-10-21（v2.8.0 发布时）。

---

## 1. 项目概况

**Embylite（原 LocalEmby）** 是一个 Android 端的本地 Emby 服务器模拟器：把手机里的本地视频目录变成一个 Emby Server，支持本机与局域网的 Emby 客户端像访问真 Emby 一样浏览、播放本地视频，并带有进度记忆、剧集识别、弹幕自动匹配、TMDB 元数据刮削。

- **仓库**：https://github.com/Dylan-1107/LocalEmby （仓库名**故意不改**，README 的下载徽章和永久下载链接都指向它）
- **应用名**：Embylite（2026-10-21 从 LocalEmby 更名）
- **包名**：`com.local.embylite` —— **故意不变**，改了老用户覆盖安装会丢设置和播放进度
- **`SERVER_ID = "localemby00000001"`** —— **故意不变**，改了 Emby 客户端会掉服务器身份
- **当前版本**：versionCode 13 / versionName **2.8.0**（见 `app/build.gradle.kts`）
- **最新 Release**：https://github.com/Dylan-1107/LocalEmby/releases/latest

### 技术栈

| 层 | 技术 |
|---|---|
| 构建 | Gradle 8.14.3 wrapper · AGP 8.13.2 · Kotlin 2.3.20 |
| UI | Jetpack Compose（Compose Multiplatform 1.10.3）+ Miuix 0.8.8（HyperOS 风格）+ Haze 1.2.2（玻璃背景模糊） |
| 服务端逻辑 | 原生 Java（无框架），内置 HTTP 服务器模拟 Emby API |
| 网络 | OkHttp + DoH 兜底（`DanmuNetwork`） |
| SDK | minSdk 26 / targetSdk 36 / compileSdk 36 |

### 核心功能一览（截至 2.8.0）

- Emby API 模拟：登录、媒体库、条目列表/详情、PlaybackInfo、图片、HTTP Range 视频流、进度上报
- 媒体目录两种授权：SAF 选文件夹（`FolderPicker.kt`）或「所有文件访问权限」全盘扫描
- 剧集识别：父目录名 + 文件名解析「剧名/季/集」，季/集层级浏览
- 弹幕自动匹配：弹弹play 兼容源 + 候选二次校验 + 指纹缓存
- TMDB 元数据刮削（2.7.0+）：唯一命中自动刮，多候选人工确认，写 Kodi 兼容 NFO 与 poster.jpg 落盘，剧集级聚合（同剧只搜一次）
- 增量识别与刮削（2.8.0+）：`LibraryIndex` 持久化扫描索引，重启/刷新只 diff 新增与变化文件；失败指数退避；任务可取消；三种模式（增量刷新 / 重试失败 / 全量重建）

---

## 2. 环境搭建与运行

### 2.1 需要自备的工具

`.gitignore` 排除了 `tools/`（本机自带 JDK + Android SDK，约 900MB）和 `*.apk`、`local.properties`，
**clone 下来的仓库里没有这些东西**，新机器需要自行准备：

1. **JDK 17**（任意发行版均可；原机器用 Eclipse Temurin 17.0.20.7.1+1）。构建时 `export JAVA_HOME=<JDK17 路径>`。
2. **Android SDK**：

   | 组件 | 版本 |
   |---|---|
   | Platform | `android-36` |
   | Build-tools | `36.0.0`（aapt2 / apksigner 在这里） |
   | Platform-tools | 较新版本（提供 adb） |

   推荐 `sdkmanager "platforms;android-36" "build-tools;36.0.0" "platform-tools"`。

3. **`local.properties`**（仓库里没有，自己建）：

   ```properties
   sdk.dir=C:/Users/<你>/Android/Sdk
   ```

### 2.2 构建命令

```bash
cd <仓库根目录>
export JAVA_HOME="<JDK17>"
export GRADLE_USER_HOME="$HOME/.gradle"   # 用默认位置

./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

### 2.3 构建踩坑记录（三条铁律 + 两个无害告警）

1. **不要设 `GRADLE_OPTS`**。曾因在里面加 `-Djava.io.tmpdir=...` 导致 TLS HEAD 全挂，
   报误导性的 `The server may not support the client's requested TLS protocol versions`。
   网络本身是通的，别被这条错误带偏。
2. **构建报 `.alive` / `AccessDeniedException` 是 tmpdir 的问题**。受限沙箱禁止 JVM 在系统 Temp 建文件时，
   在 `gradle.properties`（不是 GRADLE_OPTS）里加：
   ```properties
   org.gradle.jvmargs=-Xmx3072m -Dfile.encoding=UTF-8 -Djava.io.tmpdir=<仓库>/../.tmp/ktmp
   ```
   当前仓库的 `gradle.properties` 已含此行且指向 `D:/LocalEmby/.tmp/ktmp`——**换机器后按需改路径或删除**。
3. **第一次构建要联网**，依赖从 maven 下载需要几分钟。仓库已配置阿里云 maven 镜像（`settings.gradle.kts`），国内网络更快。
4. `./gradlew` 偶尔因 `gradle-8.14.3-bin.zip.lck` 被占而失败：删掉 `~/.gradle/wrapper/dists/gradle-8.14.3-bin/*/*.lck`，
   或直接跑 wrapper 发行目录里的 gradle。
5. Kotlin daemon 的 `AccessDeniedException: ...kotlin-daemon-client-tsmarker....tmp` 是**非致命告警**，会自动回退并继续编译。
   判断成败只看 `BUILD SUCCESSFUL / FAILED` 和 `^e: file` 行。

### 2.4 回归测试（改解析规则后必跑，纯 Java 无需设备）

```bash
export JAVA_HOME="<JDK17>"; export PATH="$JAVA_HOME/bin:$PATH"

# 剧集解析与候选校验（38 项）
javac -encoding UTF-8 -d build/regress \
  app/src/com/local/embylite/EpisodeIdentity.java \
  app/src/com/local/embylite/EpisodeMatchPolicy.java \
  app/src/com/local/embylite/Item.java \
  tests/EpisodeRegression.java
java -cp build/regress com.local.embylite.EpisodeRegression
# 期望输出：PASS 38 episode identity and candidate regression cases

# 刮削核心：标题净化 / TMDB 策略 / NFO 读写（22 项）
javac -encoding UTF-8 -d build/scrape \
  app/src/com/local/embylite/EpisodeIdentity.java \
  app/src/com/local/embylite/ScrapeTitle.java \
  app/src/com/local/embylite/ScrapePolicy.java \
  app/src/com/local/embylite/NfoData.java \
  app/src/com/local/embylite/NfoWriter.java \
  app/src/com/local/embylite/NfoParser.java \
  tests/ScrapeRegression.java
java -cp build/scrape com.local.embylite.ScrapeRegression
# 期望输出：PASS 22 scrape regression cases
```

### 2.5 装机与调试

```bash
ADB="$ANDROID_SDK/platform-tools/adb"
"$ADB" install -r app/build/outputs/apk/release/app-release.apk
"$ADB" shell am force-stop com.local.embylite
"$ADB" shell am start -n com.local.embylite/.MainActivity
"$ADB" exec-out screencap -p > shot.png

# 验证服务在跑（返回 JSON 即正常）
"$ADB" shell "curl -s -m 5 http://127.0.0.1:8096/System/Info/Public"
```

- 原被测设备：Xiaomi 17（Android 17 / API 37，1220x2656）。换设备后 UI 自动化点击坐标会变，用 `uiautomator dump` 重新取：
  ```bash
  MSYS_NO_PATHCONV=1 "$ADB" shell uiautomator dump /data/local/tmp/ui.xml
  MSYS_NO_PATHCONV=1 "$ADB" exec-out cat /data/local/tmp/ui.xml > /tmp/ui.xml
  ```
  （git-bash 下 `MSYS_NO_PATHCONV=1` 必须加，否则路径会被改写。）
- 真机毛玻璃（Miuix blur 模块）的坑见第 6 节。

---

## 3. 目录结构说明

```
LocalEmby/
├── app/
│   ├── build.gradle.kts          # 版本号（versionCode/versionName）、依赖（Miuix/Haze/OkHttp）
│   └── src/
│       ├── AndroidManifest.xml
│       ├── com/local/embylite/
│       │   ├── MainActivity.kt       # 导航壳 + 首页 HomeContent + 共享 internal 组件（Palette/Text/PillButton…）
│       │   ├── Ui.kt                 # (2.7.0+) 从 MainActivity 抽出的共享 Compose 组件
│       │   ├── GlassBar.kt           # 底部导航栏（玻璃观感）
│       │   ├── VideoInfoPage.kt      # (2.7.0+) 「视频信息」页：弹幕绑定+刮削统计、候选确认/重搜/清除
│       │   ├── VideoInfoService.java # (2.7.0+) 刮削任务编排：剧集级聚合、退避重试、取消、NFO/海报落盘
│       │   ├── TmdbClient.java       # (2.7.0+) TMDB API 客户端（search/detail/season/image）
│       │   ├── ScrapeTitle.java      # (2.7.0+) 标题净化（去发布组/分辨率/音轨噪声）
│       │   ├── ScrapePolicy.java     # (2.7.0+) 唯一命中自动刮 / 多候选待确认 的判定策略
│       │   ├── NfoData/NfoWriter/NfoParser.java  # (2.7.0+) Kodi 兼容 NFO 读写与解析导入
│       │   ├── FolderPicker.kt       # (2.8.0+) SAF 选目录（Android 11+ 不能选存储根目录）
│       │   ├── LibraryIndex.java     # (2.8.0+) 扫描结果持久化索引，刷新只 diff 新增/变化文件
│       │   ├── DanmuProxy.java       # 弹幕代理 + 弹弹play 匹配
│       │   ├── EpisodeMatcher.java   # 剧集身份解析与弹幕绑定（含指纹缓存/退避/节流）
│       │   ├── EpisodeIdentity.java / EpisodeMatchPolicy.java  # 剧集解析与候选校验
│       │   ├── VideoLibrary.java     # 媒体库扫描（目录/全盘）、文件枚举
│       │   ├── EmbyHttpServer / EmbyApi（*.java 其余部分）  # Emby API 模拟
│       │   ├── Settings.java / SettingsPage.kt  # 设置存储与设置页
│       │   ├── LogPage.kt / LogActivity.kt      # 日志页
│       │   └── MetadataStore.java    # 元数据覆盖层
│       ├── res/                      # mipmap 自适应图标、values（注意：截图不入库，仅 docs/*.png 例外）
│       └── (无 java/、res 分包，沿用非标准平铺结构以兼容手动打包脚本)
├── tests/                            # 纯 Java 回归测试（无 Android 依赖）
│   ├── EpisodeRegression.java        # 38 项剧集解析回归
│   └── ScrapeRegression.java         # 22 项刮削回归
├── docs/
│   ├── BUILD-HANDOFF.md              # 本交接文档
│   ├── session-notes-20261021.md     # Miuix 迁移踩坑实录
│   ├── miuix-api-notes.md            # Miuix 0.8.8 API 速查
│   ├── scraping-research-proposal.md # 刮削调研与立项（v2.7.0 的设计依据）
│   ├── incremental-scan-scrape-plan.md # 增量识别/刮削改造方案（v2.8.0 的设计依据）
│   ├── 立项报告-手机全量数据读取能力调研.md
│   └── screenshot-*.png              # README 用截图
├── gradle/ gradlew* build.gradle.kts settings.gradle.kts gradle.properties
├── tools/                            # （不入仓）本机 JDK17 + Android SDK
├── HyperLight-analysis/ hyperlight/  # （不入仓）第三方 APK 逆向分析工作区，仅本地参考
└── *.apk                             # （不入仓）历史构建产物；发布 APK 挂在 GitHub Release
```

**重要约定**：

- 共享 Compose 组件（`Text`、`Palette`、`PillButton`、`BasicField`、`copy`、`toast` 等）在各页面文件中为 `internal`，
  **不要重复声明同名组件或色值，会编译冲突**。2.7.0 起新组件放 `Ui.kt`。
- Git 提交用 Conventional Commits（中文说明）：`feat(ui): ...` / `fix(danmu): ...` / `docs: ...`。
- push 前确认 `gh auth status` 正常（账号 Dylan-1107）。

---

## 4. 当前版本状态

- **版本**：versionCode 13 / versionName 2.8.0，main 分支已与 GitHub 同步，最新提交 `319701b`。
- **v2.6.0 → v2.8.0 的主要变更**（上一个 Release 是 v2.5.2，中间三个版本积压后随 v2.8.0 一起发布）：
  - **v2.6.0**：更名 Embylite，迁移到 Miuix（HyperOS 风格）+ Compose 构建
  - **v2.7.0**：底部导航四页架构（首页/视频信息/日志/设置）、TMDB 元数据刮削 + NFO/海报落盘、剧集级聚合刮削
  - **v2.8.0**：增量识别与刮削（`LibraryIndex` 持久化索引、三种任务模式、退避重试、可取消）、SAF 目录选择（`FolderPicker.kt`）、自适应图标
- **发布方式**：GitHub Release + 标签（`v2.8.0`），附件为 `LocalEmby.apk`（永久链接指向）与 `LocalEmby-2.8.0.apk`；
  README 的下载徽章读取 Release，**发版必须带这两个附件**。
- **测试状态**：剧集回归 38 项、刮削回归 22 项在本机通过；Release 构建通过（APK versionName 2.8.0）。
- **本地未入仓资产**：`tools/`（JDK+SDK）、`HyperLight-analysis/`（HyperLight APK 逆向分析，含 UI 架构分析报告，UI 风格参考来源）、`hyperlight/`、历史 APK。换机器后这些不会跟仓库走。

---

## 5. 已知问题与待办事项

### 5.1 已知问题

1. **`item.id = 路径 CRC32`**（`Item.java`）：改名/移动文件后条目 ID 变化，弹幕绑定与刮削状态作废重做。
   增量方案文档建议改为内容指纹或稳定身份键，尚未实施。
2. **「全量重建」仍是重做设计**：增量框架已落地（指纹缓存、diff、退避、取消），
   但方案文档 1.2/1.3 节列的部分深层优化（剧集身份级缓存持久化、`SEASON_CACHE` 落盘）未完全实施，详见 `docs/incremental-scan-scrape-plan.md`。
3. **不做转码**：只提供直连播放，客户端需自行支持编码（Hills/YAmby 内置 mpv 支持良好）。
4. **无用户体系**：任意账号密码可登录，仅建议本机或可信局域网使用。
5. **无 WebSocket**：部分客户端实时会话推送不可用，不影响播放。
6. **`MediaStreams` 为占位信息**：部分客户端显示固定 1080p H.264/AAC，实际轨道由播放器探测。
7. **ROM 后台限制**：小米等 ROM 杀后台后服务器停止，需用户自启动/锁定后台或开启 Root 保活。

### 5.2 待办事项（接手人建议从这里开始）

1. **真机回归 v2.7.0 / v2.8.0 的 UI**：底部导航四页的切页动效、视频信息页交互、SAF 目录选择流程，
   目前仅首页截图与构建通过验证过，log/settings/folder picker 未逐页真机确认。
2. **Root 保活未实测**：需要有 Magisk/KSU 授权的设备验证 `su` 白名单路径。
3. **重拍 README 截图**：`docs/screenshot-*.png` 仍是 v2.5.x 时代的界面，底部导航与视频信息页都没体现。
4. **条目身份稳定化**（见 5.1 第 1 条），这是增量体系最大的剩余短板。
5. **状态数据导出/迁移能力**：刮削/绑定状态存 SharedPreferences 单键 JSON，无整体导出。
6. **`gradle.properties` 的 tmpdir 路径是机器相关的**（`D:/LocalEmby/.tmp/ktmp`），换机器按 2.3 节处理。

---

## 6. 附：如果要恢复「真毛玻璃」

Miuix 的毛玻璃在独立模块 `top.yukonga.miuix.kmp:miuix-blur`（不在 `miuix` 主包里）。
**踩过的致命坑**：`Modifier.layerBackdrop(backdrop)` 只捕获自身 composable 的内容；若把被模糊表面也包进捕获范围
（例如在 `fillMaxSize()` 的 Box 上挂 `layerBackdrop`，而玻璃栏就在这个 Box 内），采样源与被采样者自引用 →
**RenderThread 无限递归 → SIGSEGV，App 直接打不开**。正确做法：backdrop 挂在页面内容层（不含导航栏自身）。
另外该 AAR 要求 `minCompileSdk=37`、`minSdkVersion=31`，本仓库 minSdk 26，要用必须 `tools:overrideLibrary`
且所有 blur 调用都要 `isRuntimeShaderSupported()` 门控。当前 `GlassBar.kt` 用的是安全的等效观感替代方案。

---

## 7. 换机器检查清单

- [ ] 装 JDK 17，记下路径（构建时 `export JAVA_HOME`）
- [ ] 装 Android SDK：platform `android-36` + build-tools `36.0.0` + platform-tools
- [ ] 新建 `local.properties`，`sdk.dir` 指向新机器的 SDK
- [ ] 检查 `gradle.properties` 的 tmpdir 路径（机器相关，按需改/删）
- [ ] 第一次构建联网（别加 `--offline`），依赖下全需要几分钟
- [ ] 跑 2.4 节两套回归测试，确认 `PASS 38` 与 `PASS 22`
- [ ] 装机跑一遍，重点看 5.2 节第 1、2 条未验证项
- [ ] 发新版时记得：更新 `app/build.gradle.kts` 版本号 → 打标签 `vX.Y.Z` → Release 附件放 `LocalEmby.apk` + `LocalEmby-X.Y.Z.apk`
