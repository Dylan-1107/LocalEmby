# Embylite (原 LocalEmby) 会话笔记 — 2026-10-21

## 用户需求演进（按时间）
1. 弹幕识别拆成独立一页 + 底部导航跳转 + Miuix 风格 + 软件改名 **Embylite**
2. 设置页独立 + **Root 权限保活** + 弹幕页重排（固定头部 + 独立滚动列表，不要整条长滚动）
3. 底栏改成「上图标、下小字」（LSPosed 样式；选中为实心图标）
4. 页面切换加过渡动效（原来是直接切换）
5. 日志页从右侧滑出的独立 Activity 改成 App 内 tab，与其他页动画一致
6. 柔光玻璃（小米官方那种）效果

## 最重要的一条教训
用户对手工调优的 UI 极其敏感。第一轮我把整个首页重写，用户大怒：
「你怎么把我的UI给全部大改了？我花了好久时间才优化好的UI」
修复方式：`git checkout HEAD --` 恢复原文件，只做最小 diff。
**以后做 UI 任务必须先确认「只改哪几个组件」，不要顺手重构其他部分。**

## 构建环境（这台机器特有）
- Kotlin 编译器**无法在 `%LOCALAPPDATA%\Temp` 创建文件**（JVM `RandomAccessFile` 被沙箱拒），
  导致间歇性 `AccessDeniedException: ... kotlin-compiler-in-mbylite-*.alive`。
  已在 `embylite/gradle.properties` 加 `-Djava.io.tmpdir=C:/Users/11829/LocalEmby-Transfer/.tmp/ktmp` 修复。
- **绝对不要在 `GRADLE_OPTS` 里设 `java.io.tmpdir`**：会让 dl.google.com / repo1.maven.org 的
  TLS HEAD 失败，报误导性的 "Remote host terminated the handshake"。网络本身是通的。
- 标准构建：
  ```bash
  cd /c/Users/11829/LocalEmby-Transfer/embylite
  export JAVA_HOME="$PWD/tools/jdk-17.0.20.1+1"
  export GRADLE_USER_HOME="C:/Users/11829/LocalEmby-Transfer/.gradle-home"
  "$HOME/.gradle/wrapper/dists/gradle-8.14.3-bin/cv11ve7ro1n3o1j4so8xd9n66/gradle-8.14.3/bin/gradle" assembleRelease
  ```
  不要设 GRADLE_OPTS。wrapper 的 `.lck` 有时被占，直接用上面的 gradle 可绕开。

## miuix-blur 柔光玻璃的致命坑
Miuix 毛玻璃在**独立模块** `top.yukonga.miuix.kmp:miuix-blur`（需 RuntimeShader / Android API 33），
其 AAR 声明 `minSdkVersion=31` 且 `minCompileSdk=37`（所有版本都是，需装 android-37.0 平台）。

API（javap + 上游源码核对）：
```
rememberLayerBackdrop(onDraw: ContentDrawScope.() -> Unit)
Modifier.layerBackdrop(backdrop)
Modifier.textureBlur(backdrop, shape, blurRadius = BlurDefaults.BlurRadius,
                     noiseCoefficient, colors: BlurColors = BlurColors(),
                     highlight = null, contentBlendMode = SrcOver, enabled = true)
BlurDefaults.blurColors(blendColors, brightness, contrast, saturation)  // @Composable，优先用它
top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported()
```

**致命坑**：`layerBackdrop` 只捕获**自身 composable** 绘制的内容。若把被模糊表面自己也包进
捕获范围（例如在一个 `fillMaxSize()` 的 Box 上挂 `layerBackdrop`，而玻璃栏就在这 Box 内），
采样源与被采样者形成自引用 → RenderThread 无限递归 → **SIGSEGV，App 直接打不开**。

- `mplemented`、`blur(...)`、`blendColors(...)` 等都必须在 `isRuntimeShaderSupported()` 为 true 时调用
- Android 需 API 33；API 31/32 只能做背景记录支架（`isRenderEffectSupported()`）

**当前决定**：`GlassBar.kt` 采用安全替代方案——半透明底 + 上下不对称白色高光描边
（`Brush.verticalGradient`），不依赖 RuntimeShader，任何设备都不崩。已移除 miuix-blur 依赖，
compileSdk 回到 36。若要真毛玻璃，**backdrop 必须放在页面内容层（不含导航栏自身）**。

## Miuix 0.8.8 API 事实（javap + 上游源码核对）
| 项目 | 事实 |
|---|---|
| 动效 | `top.yukonga.miuix.kmp.anim.folmeSpring(stiffness, damping)` → `SpringSpec`；`top.yukonga.miuix.kmp.anim.SinOutEasing` 是 `val Easing`，**导入名是 `SinOutEasing`，不是 `getSinOutEasing`** |
| NavigationBar | `NavigationBarItem(selected, onClick, icon: ImageVector, label: String, modifier, enabled)`，icon/label 是**值不是 slot** |
| CardDefaults | `defaultColors(color, contentColor)`，没有 `cardColors()` |
| TopAppBar | `SmallTopAppBar(title: String)`，title 不是 composable slot |
| Text | Miuix 的 `Text(...)` **没有 `textAlign` 参数** |
| Divider | 只有 `HorizontalDivider` / `VerticalDivider`，没有 `Divider()` |
| 图标 | `MiuixIcons.Basic` 只有 5 个；`miuix-icons` 是独立 artifact 未声明，**手写 ImageVector 才对** |
| OptIn | 无 `ExperimentalMiuixApi`，上述组件都不需要 OptIn |

## 文件布局（当前）
```
app/src/com/local/embylite/
  MainActivity.kt   导航壳 + HomeContent + 底部导航调用 + 共享 internal 组件
  DanmuPage.kt      弹幕页：固定头部(进度/统计/筛选页签) + 独立滚动 LazyColumn
  SettingsPage.kt   设置页：媒体目录 / 端口 / 开机自启 / Root 保活 / 弹幕源 / 关于
  GlassBar.kt       底部导航：上实心图标下小字；半透明底 + 玻璃高光描边
  LogPage.kt        日志页 Composable（nav tab 与 LogActivity 共用）
  LogActivity.kt    薄壳（LogPage(p, onBack = finish())）
  RootKeepAlive.java su 白名单/解除后台限制/拉起前台服务（需用户显式开启）
```
- `Ui.kt` 与 `AppState.kt` 是第一轮产物，已删除，**不要再引入**。
- 共享组件在 MainActivity.kt 里声明为 `internal`：`Blue/Red/Orange/Green`、`Palette`、
  `DarkPalette/LightPalette`、`Text`、`PillButton`、`BasicField`、`copy`、`toast`。
  新文件直接复用，**不要重复声明 `Text` 或色值**（会冲突）。

## 设备信息
- adb：`embylite/tools/sdk/platform-tools/adb.exe`，设备 `ee8617da`
- Xiaomi 17，Android 17 (API 37)，屏幕 1220x2656
- 底栏点击坐标：首页(328,2471) 弹幕(516,2471) 日志(704,2471) 设置(892,2471)
- `uiautomator dump` 必须加 `MSYS_NO_PATHCONV=1`，否则 git-bash 改写路径读不到文件

## 版本 / 命名
- `versionCode 11` / `versionName 2.6.0`，label = **Embylite**
- 包名 `com.local.embylite` 与 `SERVER_ID = "localemby00000001"` **故意不改**（老用户升级会丢设置与播放进度）
- 刚装过但**尚未真机验证**的新版本：底部导航 + 页面过渡动效 + 日志 tab 化
