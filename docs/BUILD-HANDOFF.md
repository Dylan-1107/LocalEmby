# 构建环境与交接说明

> 这份文档记录本仓库在**当前这台机器**上的完整构建环境、踩过的坑、以及未完成的验证项。
> 换电脑/换环境时**先读这份**，能省掉半天时间。最后更新：2026-10-21。

---

## 1. 这个仓库是什么

本地 Emby 服务器模拟器（Android）。把手机上的本地视频目录变成一个 Emby Server，
支持本机 `http://127.0.0.1:8096` 和局域网访问、播放进度续播、剧集识别、弹幕自动匹配。

- **应用名**：Embylite（原名 LocalEmby，2026-10-21 更名）
- **包名**：`com.local.embylite` —— **故意不变**，改了老用户升级会丢设置和播放进度
- **`SERVER_ID = "localemby00000001"`** —— **故意不变**，改了这个 Emby 客户端会掉服务器身份
- **版本**：versionCode 11 / versionName 2.6.0
- **GitHub**：https://github.com/Dylan-1107/LocalEmby （仓库名仍是 LocalEmby，别改，下载链接靠它）

技术栈：AGP 8.13.2 · Kotlin 2.3.20 · Compose Multiplatform 1.10.3 · Miuix 0.8.8 · minSdk 26 / targetSdk 36。
UI 是 Jetpack Compose + Miuix（HyperOS 风格卡片），服务端逻辑是原生 Java。

---

## 2. 目录里自带了什么（不用另装 SDK/JDK）

```
embylite/
  tools/
    jdk-17.0.20.7.1+1/     # JDK 17，直接用这个
    sdk/
      platforms/android-36/   # compileSdk 36
      build-tools/36.0.0/     # aapt2 / apksigner 在这里
      platform-tools/         # adb
  local.properties          # sdk.dir 指向 tools/sdk
  gradle/wrapper/           # Gradle 8.14.3
```

所以**不需要单独装 JDK、Android SDK**，仓库自带。`local.properties` 里是绝对路径，
换机器要改。

---

## 3. 标准构建命令（重要，有坑）

```bash
cd /c/Users/11829/LocalEmby-Transfer/embylite

export JAVA_HOME="$PWD/tools/jdk-17.0.20.1+1"
export GRADLE_USER_HOME="C:/Users/11829/LocalEmby-Transfer/.gradle-home"

# 直接用 gradle 发行版里的 gradle，别用 ./gradlew（wrapper 的 .lck 有时被占）
"$HOME/.gradle/wrapper/dists/gradle-8.14.3-bin/cv11ve7ro1n3o1j4so8xd9n66/gradle-8.14.3/bin/gradle" \
    --offline assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`

### 三条铁律

1. **不要设 `GRADLE_OPTS`**。
   曾经在里面加过 `-Djava.io.tmpdir=...`，结果 dl.google.com / repo1.maven.org 的 TLS HEAD
   直接失败，报的是误导性的 `The server may not support the client's requested TLS protocol
   versions` / `Remote host terminated the handshake`。**网络本身是通的**，别被这条错误带偏去查 TLS。

2. **`gradle.properties` 里必须有 `-Djava.io.tmpdir`**。
   这台机器的沙箱**禁止 JVM 在 `%LOCALAPPDATA%\Temp` 创建新文件**（`RandomAccessFile` 被拒），
   Kotlin 编译器会间歇性报：
   ```
   java.nio.file.AccessDeniedException: ...\Temp\kotlin-compiler-in-mbylite-18105....alive
   ```
   已通过 `gradle.properties` 修复：
   ```properties
   org.gradle.jvmargs=-Xmx3072m -Dfile.encoding=UTF-8 -Djava.io.tmpdir=C:/Users/11829/LocalEmby-Transfer/.tmp/ktmp
   ```
   **换电脑如果不再有沙箱限制，这行可以去掉；但如果又报 `.alive` / AccessDenied，就是这个原因。**

3. **不需要联网**。所有依赖（Miuix、AGP、Compose、OkHttp）都已在
   `C:\Users\11829\LocalEmby-Transfer\.gradle-home\caches` 里，所以用 `--offline`。
   如果要加新依赖，去掉 `--offline` 让它下载（命令见上，别设 GRADLE_OPTS）。

### 关于 Kotlin daemon 的一条无害告警

编译时可能看到：
```
AccessDeniedException: C:\Users\11829\AppData\Local\kotlin\daemon\kotlin-daemon-client-tsmarker....tmp
```
**这是非致命的**，Kotlin 编译器会回退并继续编译，最后仍然 `BUILD SUCCESSFUL`。
别去修它，也别因为它以为构建失败了。判断成败只看 `BUILD SUCCESSFUL / FAILED` 和 `^e: file` 行。

---

## 4. 回归测试（改命名规则后必跑）

剧集解析和候选校验是纯 Java 逻辑，不依赖设备。**改了任何 `SxxExx` / `第N集` / 命名规则后先跑这个**：

```bash
export JAVA_HOME="$PWD/tools/jdk-17.0.20.1+1"; export PATH="$JAVA_HOME/bin:$PATH"
javac -encoding UTF-8 -d build/regress \
  app/src/com/local/embylite/EpisodeIdentity.java \
  app/src/com/local/embylite/EpisodeMatchPolicy.java \
  app/src/com/local/embylite/Item.java \
  tests/EpisodeRegression.java
java -cp build/regress com.local.embylite.EpisodeRegression
# 期望：PASS 38 episode identity and candidate regression cases
```

---

## 5. 装机与调试

```bash
ADB="$PWD/tools/sdk/platform-tools/adb.exe"

"$ADB" devices                                   # ee8617da = 这台机上的 Xiaomi 17
"$ADB" install -r app/build/outputs/apk/release/app-release.apk
"$ADB" shell am force-stop com.local.embylite
"$ADB" shell am start -n com.local.embylite/.MainActivity
"$ADB" exec-out screencap -p > shot.png

# 看服务端是否真的在跑（返回 JSON 就是好的）
"$ADB" shell "curl -s -m 5 http://127.0.0.1:8096/System/Info/Public"
"$ADB" shell "curl -s -m 8 'http://127.0.0.1:8096/api/episodes/status'"
"$ADB" shell "curl -s -m 15 'http://127.0.0.1:8096/api/danmu/<itemId>?option=GetJsonById'"

# APK 元数据（验证 label / versionCode）
"$PWD/tools/sdk/build-tools/36.0.0/aapt2.exe" dump badging app/build/outputs/apk/release/app-release.apk
```

被测设备：Xiaomi 17，Android 17 (API 37)，屏幕 1220x2656。

**点击坐标**（adb input tap，这台设备的物理像素）：
- 底部导航四项：首页 (328,2471) · 弹幕 (516,2471) · 日志 (704,2471) · 设置 (892,2471)

**取 UI 元素真实坐标**（比猜坐标靠谱）：
```bash
MSYS_NO_PATHCONV=1 "$ADB" shell uiautomator dump /data/local/tmp/ui.xml
MSYS_NO_PATHCONV=1 "$ADB" exec-out cat /data/local/tmp/ui.xml > /tmp/ui.xml
```
`MSYS_NO_PATHCONV=1` 必须加，否则 git-bash 会把 `/data/local/tmp` 改写成 Windows 路径，读不到文件。

---

## 6. 跑 Gradle 依赖检查（可选）

```bash
"$HOME/.gradle/.../gradle" -p app dependencies --configuration releaseRuntimeClasspath
```
注意配置名要在 `:app` 子项目上，根项目没有这个 configuration。

---

## 7. 编译产物 / 文件布局

```
app/src/com/local/embylite/
  MainActivity.kt      导航壳 + HomeContent(首页) + 共享 internal 组件(Palette/Text/PillButton…)
  DanmuPage.kt         弹幕页：固定头部(进度/统计/筛选页签) + 独立滚动 LazyColumn
  SettingsPage.kt      设置页：媒体目录/端口/开机自启/Root 保活/弹幕源/关于
  GlassBar.kt          底部导航：上实心图标下小字；半透明底 + 玻璃高光描边
  LogPage.kt           日志页 Composable（底部导航「日志」页与 LogActivity 共用）
  LogActivity.kt       薄壳，只包一层 LogPage
  *.java               EmbyHttpServer / EmbyApi / VideoLibrary / EpisodeMatcher / RootKeepAlive / HttpService …
docs/
  session-notes-20261021.md   本次会话的详细踩坑记录（Miuix API 真相、bug 根因）
  miuix-api-notes.md          Miuix 0.8.8 API 速查（javap + 上游源码核对过）
  screenshot-*.png            README 用图
```

**共享组件都在 MainActivity.kt 里且是 `internal`**：`Blue/Red/Orange/Green`、`Palette`、
`DarkPalette/LightPalette`、`Text`、`PillButton`、`BasicField`、`copy`、`toast`。
新页面直接复用，**不要重复声明 `Text` 或色值，会直接冲突（编译不过）**。

---

## 8. 未完成的验证项（接手第一件事）

1. **底部导航 + 页面过渡动效只在一张首页截图上验证过**。log/danmu/settings 三个 tab 的
   视觉效果和切页动画**未真机确认**（设备当时被其他 App 占用随后断开）。
2. **Root 保活未实测**：被测设备虽然装了 LSPosed，但 App 调 `su` 时未授权，
   状态显示「无 root」。需要有 Magisk/KSU 授权的设备实测。
3. **README 截图可能过期**：底部导航和页面结构都变了，`docs/screenshot-*.png` 需要重拍。

---

## 9. 如果要恢复「真毛玻璃」

Miuix 的毛玻璃在独立模块 `top.yukonga.miuix.kmp:miuix-blur`（不是 `miuix` 的一部分）。
**踩过的致命坑**：`Modifier.layerBackdrop(backdrop)` 只捕获自身 composable 的内容；如果把被模糊的
表面自己也包进捕获范围（例如在 `fillMaxSize()` 的 Box 上挂 `layerBackdrop`，而玻璃栏就在这个 Box 内），
采样源与被采样者形成自引用 → **RenderThread 无限递归 → SIGSEGV，App 直接打不开**。

正确做法：backdrop 必须挂在**页面内容层**（不含导航栏自身）。
另外该 AAR 声明 `minCompileSdk=37`、`minSdkVersion=31`，全部版本都是；本仓库 minSdk 26，
要用必须加 `tools:overrideLibrary` 并且所有 blur 调用都要 `isRuntimeShaderSupported()` 门控。
详见 `docs/session-notes-20261021.md`。当前 `GlassBar.kt` 用的是安全的等效观感替代方案。

---

## 10. Git 提交规范

仓库用 Conventional Commits（中文说明），例如：

```
feat(ui): 底部导航改图标+文字，切页加过渡动效
fix(danmu): 修复候选确认后列表不刷新
docs: 补充构建环境交接说明
```

push 前确认 `gh auth status` 正常（当前账号 Dylan-1107，token scope 有 `repo`）。

---

## 11. 换机器检查清单

- [ ] 改 `local.properties` 里的 `sdk.dir`（绝对路径，指向新机器上的 `embylite/tools/sdk`）
- [ ] 改 `gradle.properties` 里的 `-Djava.io.tmpdir`（如果新机器也有 JVM 不能写 Temp 的问题）
- [ ] 第一次构建去掉 `--offline`，让依赖下全（或把 `.gradle-home/caches` 拷过去）
- [ ] 跑一遍第 4 节的回归测试，确认输出 `PASS 38`
- [ ] 装机跑一遍，重点看第 8 节那三条未验证项
