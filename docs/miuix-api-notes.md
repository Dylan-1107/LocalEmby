# Miuix 0.8.8 — API Usage Cheat Sheet (compile-verified)

Target: `top.yukonga.miuix.kmp:miuix:0.8.8` used by `embylite`
(Kotlin 2.3.20 · Compose Multiplatform 1.10.3 · minSdk 26 · compileSdk 36).

**Verification method.** Every signature below was read from the upstream Kotlin source at
tag `v0.8.8` (`https://github.com/compose-miuix-ui/miuix`, tag confirmed to exist — it is
`v0.8.8`, with the `v`), then cross-checked with `javap` against the actual
`javap` against the actual `miuix.aar` → `classes.jar` in the Gradle cache. In addition, all code
in this document was **actually compiled** with `kotlinc 2.3.20` + Compose compiler plugin 2.3.20
against that AAR (plus `miuix-icons` 0.8.8 for the extended-icon variant). Anything not verified
is called out explicitly in §12.

---

## 0. Artifacts & dependencies

The cached module metadata (`miuix-android-0.8.8.module`, `miuix-icons-android-0.8.8.module`)
shows exactly this graph:

| Artifact | Notes |
| --- | --- |
| `top.yukonga.miuix.kmp:miuix:0.8.8` | the library (already declared in `app/build.gradle.kts:74`) |
| `top.yukonga.miuix.kmp:miuix-icons:0.8.8` | **separate optional artifact** — the extended icon set. **Not** pulled in by `miuix` |
| `org.jetbrains.compose.foundation:foundation:1.10.3` | transitive (api) |
| `org.jetbrains.kotlin:kotlin-stdlib:2.3.20` | transitive (api) |
| `org.jetbrains.androidx.navigationevent:navigationevent-compose:1.0.1` | runtime only |
| `org.jetbrains.compose.material3:material3-window-size-class:1.9.0` | runtime only |
| `io.github.kyant0:shapes:1.2.0` | runtime only |
| `com.materialkolor:material-color-utilities:4.1.1` | runtime only |

### Where icons come from — important

There are **two different icon sources**, and they are not the same thing:

1. **Bundled inside `miuix` itself** — only 5 icons, in package `top.yukonga.miuix.kmp.icon.basic`,
   on the `MiuixIcons.Basic` receiver:
   `Check`, `Search`, `SearchCleanup`, `ArrowRight`, `ArrowUpDown`.
   These need **no extra dependency**. `miuix-icons` 0.8.8 ships **no** `MiuixIcons` object of its
   own (verified: the 0.8.8 icons AAR contains only `top/yukonga/miuix/kmp/icon/extended/*`).

2. **The `miuix-icons` artifact** — ~155 icons in package
   `top.yukonga.miuix.kmp.icon.extended`, attached to the `MiuixIcons` receiver
   (`MiuixIcons.Add`, `MiuixIcons.Settings`, …) plus the weight variants
   `MiuixIcons.Light.X` / `MiuixIcons.Regular.X` / `MiuixIcons.Heavy.X`.

   ⚠️ The current `app/build.gradle.kts` does **not** declare `miuix-icons`, and the Gradle cache
   only holds `miuix-icons` **0.9.4** binaries (only `.module`/`.pom` for 0.8.8). To use extended
   icons you must add:

   ```kotlin
   implementation("top.yukonga.miuix.kmp:miuix-icons:0.8.8")
   ```

   Until then, **only the 5 `MiuixIcons.Basic.*` icons exist on the compile classpath** — this is
   exactly what `Ui.kt` already works around by hand-rolling `ImageVector`s with `path {}`.

`MiuixIcons` object (source `miuix/src/commonMain/kotlin/top/yukonga/miuix/kmp/icon/MiuixIcons.kt`, verbatim):

```kotlin
package top.yukonga.miuix.kmp.icon

object MiuixIcons {
    object Basic
    object Light
    object Regular
    object Heavy
}
```

Note there is **no `MiuixIcons.Useful`** — that name does not exist in 0.8.8 (or 0.9.x).
`Basic` is the 26dp bundled set; `Light`/`Regular`/`Heavy` are the 24dp extended weight sets.

Icon usage requires an explicit import of the extension property, e.g.
`import top.yukonga.miuix.kmp.icon.basic.Check` → `MiuixIcons.Basic.Check`.
Without that import the reference does not resolve (verified by compiler error).

---

## 1. Theme

### 1.1 `MiuixTheme` — two overloads

`top/yukonga/miuix/kmp/theme/MiuixTheme.kt`

```kotlin
package top.yukonga.miuix.kmp.theme

@Composable
fun MiuixTheme(
    controller: ThemeController,
    textStyles: TextStyles = MiuixTheme.textStyles,
    smoothRounding: Boolean = true,
    content: @Composable () -> Unit,
)

@Composable
fun MiuixTheme(
    colors: Colors = MiuixTheme.colorScheme,
    textStyles: TextStyles = MiuixTheme.textStyles,
    smoothRounding: Boolean = true,
    content: @Composable () -> Unit,
)
```

`MiuixTheme { ... }` with **no arguments compiles fine** (verified) — it resolves to the `colors`
overload. The `controller` overload can never be selected implicitly because `controller` has no
default.

`javap` confirmation (`MiuixThemeKt`):

```
MiuixTheme(ThemeController, TextStyles, boolean, Function2, ...)
MiuixTheme(Colors,          TextStyles, boolean, Function2, ...)
```

### 1.2 `ThemeController`

`top/yukonga/miuix/kmp/theme/ThemeController.kt`

```kotlin
@Stable
class ThemeController(
    colorSchemeMode: ColorSchemeMode = ColorSchemeMode.System,
    lightColors: Colors = lightColorScheme(),
    darkColors: Colors = darkColorScheme(),
    keyColor: Color? = null,
    colorSpec: ThemeColorSpec = ThemeColorSpec.Spec2021,
    paletteStyle: ThemePaletteStyle = ThemePaletteStyle.TonalSpot,
    isDark: Boolean? = null,
) {
    val colorSchemeMode: ColorSchemeMode
    val lightColors: Colors
    val darkColors: Colors
    val keyColor: Color?
    val colorSpec: ThemeColorSpec
    val paletteStyle: ThemePaletteStyle
    val isDark: Boolean?

    @Composable fun currentColors(): Colors
}
```

Enums (all `@Stable`, all in `top.yukonga.miuix.kmp.theme`):

```kotlin
enum class ColorSchemeMode { System, Light, Dark, MonetSystem, MonetLight, MonetDark }
enum class ThemeColorSpec  { Spec2021, Spec2025 }
enum class ThemePaletteStyle { TonalSpot, Neutral, Vibrant, Expressive, Rainbow,
                               FruitSalad, Monochrome, Fidelity, Content }
```

Instantiate once and remember it (it holds `mutableStateOf` fields, so it survives recomposition
and can be mutated to switch themes live):

```kotlin
val controller = remember { ThemeController(ColorSchemeMode.System) }
MiuixTheme(controller = controller) { /* ... */ }
```

`Spec2025` is silently downgraded to `Spec2021` for palette styles other than
`TonalSpot/Neutral/Vibrant/Expressive` (see `colorsFromSeed`).

### 1.3 `MiuixTheme.colorScheme` / `MiuixTheme.textStyles`

```kotlin
object MiuixTheme {
    val colorScheme: Colors            @Composable @ReadOnlyComposable get()
    val textStyles: TextStyles         @Composable @ReadOnlyComposable get()
    val colorSchemeMode: ColorSchemeMode? @Composable @ReadOnlyComposable get()
    val smoothRounding: Boolean        @Composable @ReadOnlyComposable get()
    val isDynamicColor: Boolean        @Composable @ReadOnlyComposable get()
}
```

Both are `@Composable` getters — readable **only inside `@Composable` code**. Reading them inside
a `remember { }` block lambda is fine (the lambda is inlined into the composable), but not from a
plain non-composable helper.

`Colors` properties (all `var ... by mutableStateOf`, `top.yukonga.miuix.kmp.theme.Colors`):

```
primary, onPrimary, primaryVariant, onPrimaryVariant,
error, onError, errorContainer, onErrorContainer,
disabledPrimary, disabledOnPrimary, disabledPrimaryButton, disabledOnPrimaryButton,
disabledPrimarySlider, primaryContainer, onPrimaryContainer,
secondary, onSecondary, secondaryVariant, onSecondaryVariant,
disabledSecondary, disabledOnSecondary, disabledSecondaryVariant, disabledOnSecondaryVariant,
secondaryContainer, onSecondaryContainer, secondaryContainerVariant, onSecondaryContainerVariant,
tertiaryContainer, onTertiaryContainer, tertiaryContainerVariant,
background, onBackground, onBackgroundVariant,
surface, onSurface, surfaceVariant, onSurfaceSecondary,
onSurfaceVariantSummary, onSurfaceVariantActions, disabledOnSurface,
surfaceContainer, onSurfaceContainer, onSurfaceContainerVariant,
surfaceContainerHigh, onSurfaceContainerHigh,
surfaceContainerHighest, onSurfaceContainerHighest,
outline, dividerLine, windowDimming,
sliderKeyPoint, sliderKeyPointForeground, sliderBackground
```

Color-scheme factories (all params defaulted; 53 `Color` params each):

```kotlin
fun lightColorScheme(
    primary: Color = Color(0xFF3482FF),
    onPrimary: Color = Color.White,
    primaryVariant: Color = Color(0xFF3482FF),
    onPrimaryVariant: Color = Color(0xFFAECDFF),
    /* ... 49 more, all defaulted ... */
): Colors

fun darkColorScheme(/* same shape, dark defaults */): Colors
```

`TextStyles` (`top.yukonga.miuix.kmp.theme.TextStyles`) fields:
`main, paragraph, body1, body2, button, footnote1, footnote2, headline1, headline2, subtitle,
title1, title2, title3, title4` — all `var ... by mutableStateOf`.
Factory: `fun defaultTextStyles(main = …, paragraph = …, …): TextStyles`.
Default sizes: `main`/`paragraph`/`button` 17sp, `body1` 16sp, `body2` 14sp,
`footnote1` 13sp, `footnote2` 11sp, `paragraph` has `lineHeight = 1.2.em`.

---

## 2. Scaffold

`top/yukonga/miuix/kmp/basic/Scaffold.kt`

```kotlin
package top.yukonga.miuix.kmp.basic

@Composable
fun Scaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    floatingActionButtonPosition: FabPosition = FabPosition.End,
    floatingToolbar: @Composable () -> Unit = {},
    floatingToolbarPosition: ToolbarPosition = ToolbarPosition.BottomCenter,
    snackbarHost: @Composable () -> Unit = {},
    popupHost: @Composable () -> Unit = { MiuixPopupHost() },
    containerColor: Color = MiuixTheme.colorScheme.surface,
    contentWindowInsets: WindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout),
    content: @Composable (PaddingValues) -> Unit,
)
```

Notes:
- `content` receives `PaddingValues` — it is **not** applied for you; consume `padding` yourself.
- `FabPosition` is a value class with `Start, Center, End, EndOverlay`.
- `ToolbarPosition` is a value class with `TopStart, CenterStart, BottomStart, TopEnd, CenterEnd,
  BottomEnd, TopCenter, BottomCenter`.
- `popupHost` defaults to `MiuixPopupHost()` — do not override unless you know why.

---

## 3. NavigationBar

`top/yukonga/miuix/kmp/basic/NavigationBar.kt`

```kotlin
@Composable
fun NavigationBar(
    modifier: Modifier = Modifier,
    color: Color = MiuixTheme.colorScheme.surface,
    showDivider: Boolean = true,
    defaultWindowInsetsPadding: Boolean = true,
    mode: NavigationBarDisplayMode = NavigationBarDisplayMode.IconAndText,
    content: @Composable RowScope.() -> Unit,
)

@Composable
fun RowScope.NavigationBarItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
)
```

`icon` is an `ImageVector` and `label` is a plain `String` — **not** composable slots.

```kotlin
enum class NavigationBarDisplayMode { IconAndText, IconOnly, TextOnly, IconWithSelectedLabel }

@Immutable
data class NavigationItem(val label: String, val icon: ImageVector)

object NavigationBarDefaults {
    val ItemHeight = 64.dp
    val ItemHeightIOS = 48.dp
    val IconSize = 26.dp
    val LabelFontSize = 12.sp
    val IconTopPadding = 8.dp
    val BottomPadding = 8.dp
    val SelectedPressedAlpha = 0.5f
    val UnselectedPressedAlpha = 0.6f
    val UnselectedAlpha = 0.4f
}
```

Also available: `FloatingNavigationBar` / `FloatingNavigationBarItem` /
`FloatingNavigationBarDefaults` / `FloatingNavigationBarDisplayMode` and
`NavigationRail` / `NavigationRailItem` (`basic/NavigationRail.kt`).
`NavigationBar` documents "2 to 5 items" — 4 items is fine.

---

## 4. Top app bars

`top/yukonga/miuix/kmp/basic/TopAppBar.kt`

```kotlin
@Composable
@NonRestartableComposable
fun SmallTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    color: Color = MiuixTheme.colorScheme.surface,
    titleColor: Color = MiuixTheme.colorScheme.onSurface,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: ScrollBehavior? = null,
    defaultWindowInsetsPadding: Boolean = true,
    horizontalPadding: Dp = TopAppBarDefaults.HorizontalPadding,
)

@Composable
fun TopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    color: Color = MiuixTheme.colorScheme.surface,
    titleColor: Color = MiuixTheme.colorScheme.onSurface,
    largeTitle: String = title,
    largeTitleColor: Color = MiuixTheme.colorScheme.onSurface,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: ScrollBehavior? = null,
    defaultWindowInsetsPadding: Boolean = true,
    horizontalPadding: Dp = TopAppBarDefaults.HorizontalPadding,
)
```

⚠️ **`title` is a `String`, not a `@Composable` slot** in 0.8.8. (Material3-style
`title: @Composable () -> Unit` does **not** exist here.) For a custom title, pass `title = ""`
and put your composable in `navigationIcon`/`actions`, or wrap the bar yourself.

Scroll behavior:

```kotlin
@Composable
fun MiuixScrollBehavior(
    state: TopAppBarState = rememberTopAppBarState(),
    canScroll: () -> Boolean = { true },
    snapAnimationSpec: AnimationSpec<Float>? = spring(stiffness = 2500f),
    flingAnimationSpec: DecayAnimationSpec<Float>? = rememberSplineBasedDecay(),
): ScrollBehavior

@Composable
fun rememberTopAppBarState(
    initialHeightOffsetLimit: Float = -Float.MAX_VALUE,
    initialHeightOffset: Float = 0f,
    initialContentOffset: Float = 0f,
): TopAppBarState

interface ScrollBehavior {
    val state: TopAppBarState
    val isPinned: Boolean
    val snapAnimationSpec: AnimationSpec<Float>?
    val flingAnimationSpec: DecayAnimationSpec<Float>?
    val nestedScrollConnection: NestedScrollConnection
}

object TopAppBarDefaults {
    val HorizontalPadding = 26.dp
    val CollapsedHeight = 56.dp
    val SmallTopAppBarCenterHeight = 60.dp
}
```

`ScrollBehavior` is a Miuix interface (`top.yukonga.miuix.kmp.basic.ScrollBehavior`), **not**
`androidx.compose.material3.TopAppBarScrollBehavior`. To make it work, attach it on the scrolling
content: `Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)`.
`SmallTopAppBar` forcibly sets `heightOffsetLimit = 0f`, i.e. it does **not** collapse — it only
needs the behavior for the content offset/status-bar handling.

---

## 5. Card

`top/yukonga/miuix/kmp/basic/Card.kt`

```kotlin
@Composable
@NonRestartableComposable
fun Card(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = CardDefaults.CornerRadius,
    insideMargin: PaddingValues = CardDefaults.InsideMargin,
    colors: CardColors = CardDefaults.defaultColors(),
    content: @Composable ColumnScope.() -> Unit,
)

// clickable variant
@Composable
fun Card(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = CardDefaults.CornerRadius,
    insideMargin: PaddingValues = CardDefaults.InsideMargin,
    colors: CardColors = CardDefaults.defaultColors(),
    pressFeedbackType: PressFeedbackType = PressFeedbackType.None,
    showIndication: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
)

object CardDefaults {
    val CornerRadius = 16.dp
    val InsideMargin = PaddingValues(0.dp)

    @Composable
    fun defaultColors(
        color: Color = MiuixTheme.colorScheme.surfaceContainer,
        contentColor: Color = MiuixTheme.colorScheme.onSurfaceContainer,
    ): CardColors
}

@Immutable
data class CardColors(val color: Color, val contentColor: Color)
```

⚠️ The factory is **`CardDefaults.defaultColors(...)`**, *not* `CardDefaults.cardColors(...)`.
There is no `cardColors` in 0.8.8 (verified against both source and `javap`:
`defaultColors-dgg9oW8`). `PressFeedbackType` lives in `top.yukonga.miuix.kmp.utils` and is
`{ None, Sink, Tilt }`.

`Card { }` with only a trailing lambda compiles unambiguously (verified) — it binds to the
non-clickable overload.

`Card` sets `LocalContentColor` to `colors.contentColor`, so `Text` inside inherits it.

---

## 6. Text

`top/yukonga/miuix/kmp/basic/Text.kt`

```kotlin
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    autoSize: TextAutoSize? = null,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = LocalTextStyles.current.main,
)

@Composable
fun Text(
    text: AnnotatedString,
    /* same params, plus: */
    inlineContent: Map<String, InlineTextContent> = mapOf(),
    onTextLayout: (TextLayoutResult) -> Unit = {},
    style: TextStyle = LocalTextStyles.current.main,
)
```

Default `style` is `main` (17sp). Color resolution:
`color.takeOrElse { style.color.takeOrElse { LocalContentColor.current } }`.
`LocalContentColor` here is `top.yukonga.miuix.kmp.theme.LocalContentColor` (defaults to
`Color.Black`), provided by Miuix `Surface`/`Card` — **not** `androidx.compose.material3`.

---

## 7. Other components

### Switch
```kotlin
@Composable
fun Switch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    colors: SwitchColors = SwitchDefaults.switchColors(),
    enabled: Boolean = true,
)
```
`onCheckedChange` is **nullable and has no default** — you must pass it (pass `null` to make it
read-only). `SwitchDefaults.switchColors(...)` takes 8 optional colors:
`checkedThumbColor`, `uncheckedThumbColor`, `disabledCheckedThumbColor`,
`disabledUncheckedThumbColor`, `checkedTrackColor`, `uncheckedTrackColor`,
`disabledCheckedTrackColor`, `disabledUncheckedTrackColor`.

### Button / TextButton
```kotlin
@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    cornerRadius: Dp = ButtonDefaults.CornerRadius,
    minWidth: Dp = ButtonDefaults.MinWidth,
    minHeight: Dp = ButtonDefaults.MinHeight,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    insideMargin: PaddingValues = ButtonDefaults.InsideMargin,
    interactionSource: MutableInteractionSource? = null,
    indication: Indication? = LocalIndication.current,
    content: @Composable RowScope.() -> Unit,
)

@Composable
fun TextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    cornerRadius: Dp = ButtonDefaults.CornerRadius,
    minWidth: Dp = ButtonDefaults.MinWidth,
    minHeight: Dp = ButtonDefaults.MinHeight,
    colors: TextButtonColors = ButtonDefaults.textButtonColors(),
    insideMargin: PaddingValues = ButtonDefaults.InsideMargin,
    interactionSource: MutableInteractionSource? = null,
    indication: Indication? = LocalIndication.current,
)
```
`Button` takes a composable `content: @Composable RowScope.() -> Unit`;
`TextButton` takes a **`text: String`** (no content slot).
`ButtonDefaults`: `MinWidth = 58.dp`, `MinHeight = 40.dp`, `CornerRadius = 16.dp`,
`InsideMargin = PaddingValues(horizontal = 16.dp, vertical = 13.dp)`.
Factories: `buttonColors(color, disabledColor, contentColor, disabledContentColor)`,
`buttonColorsPrimary()`, `textButtonColors(color, disabledColor, textColor, disabledTextColor)`,
`textButtonColorsPrimary()`.

### IconButton
```kotlin
@Composable
fun IconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    holdDownState: Boolean = false,
    backgroundColor: Color = Color.Unspecified,
    cornerRadius: Dp = IconButtonDefaults.CornerRadius,
    minHeight: Dp = IconButtonDefaults.MinHeight,
    minWidth: Dp = IconButtonDefaults.MinWidth,
    content: @Composable () -> Unit,
)

object IconButtonDefaults {
    val MinWidth = 40.dp
    val MinHeight = 40.dp
    val CornerRadius = 40.dp
}
```

### Icon
```kotlin
@Composable
fun Icon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
)
// plus overloads for ImageBitmap / Painter / (Painter, ColorProducer)
```

### TextField (BasicTextField-style, `top.yukonga.miuix.kmp.basic.TextField`)

Three overloads — the `TextFieldState` one (current foundation API) plus the two legacy ones:

```kotlin
@Composable
fun TextField(
    state: TextFieldState,                 // androidx.compose.foundation.text.input.TextFieldState
    modifier: Modifier = Modifier,
    insideMargin: DpSize = TextFieldDefaults.InsideMargin,
    backgroundColor: Color = MiuixTheme.colorScheme.secondaryContainer,
    cornerRadius: Dp = TextFieldDefaults.CornerRadius,
    label: String = "",
    labelColor: Color = MiuixTheme.colorScheme.onSecondaryContainer,
    borderColor: Color = MiuixTheme.colorScheme.primary,
    useLabelAsPlaceholder: Boolean = false,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    inputTransformation: InputTransformation? = null,
    textStyle: TextStyle = MiuixTheme.textStyles.main,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    onKeyboardAction: KeyboardActionHandler? = null,
    lineLimits: TextFieldLineLimits = TextFieldLineLimits.Default,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    onTextLayout: (Density.(getResult: () -> TextLayoutResult?) -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null,
    cursorBrush: Brush = SolidColor(borderColor),
    outputTransformation: OutputTransformation? = null,
    scrollState: ScrollState = rememberScrollState(),
)

@Composable
fun TextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    /* same visual params ... */
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    /* ... */
)

@Composable
fun TextField(
    value: String,
    onValueChange: (String) -> Unit,
    /* same visual params ... */
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    /* ... */
)

object TextFieldDefaults {
    val CornerRadius = 16.dp
    val InsideMargin = DpSize(16.dp, 16.dp)
}
```

`label` is a `String`; it floats above the field. `useLabelAsPlaceholder = true` makes the empty
state show it inline instead. There is **no** `placeholder`/`supportingText`/`isError` param.

### ProgressIndicator (`top.yukonga.miuix.kmp.basic`)
```kotlin
@Composable
fun LinearProgressIndicator(
    modifier: Modifier = Modifier,
    progress: Float? = null,          // null = indeterminate
    colors: ProgressIndicatorColors = ProgressIndicatorDefaults.progressIndicatorColors(),
    height: Dp = ProgressIndicatorDefaults.DefaultLinearProgressIndicatorHeight,
)

@Composable
fun CircularProgressIndicator(
    modifier: Modifier = Modifier,
    progress: Float? = null,
    colors: ProgressIndicatorColors = ProgressIndicatorDefaults.progressIndicatorColors(),
    strokeWidth: Dp = ProgressIndicatorDefaults.DefaultCircularProgressIndicatorStrokeWidth,
    size: Dp = ProgressIndicatorDefaults.DefaultCircularProgressIndicatorSize,
)

@Composable
fun InfiniteProgressIndicator(/* modifier, color, strokeWidth, size, orbitingDotSize */)

object ProgressIndicatorDefaults {
    val DefaultLinearProgressIndicatorHeight = 6.dp
    val DefaultCircularProgressIndicatorStrokeWidth = 4.dp
    val DefaultCircularProgressIndicatorSize = 30.dp
    val DefaultInfiniteProgressIndicatorStrokeWidth = 2.dp
    val DefaultInfiniteProgressIndicatorOrbitingDotSize = 2.dp
    val DefaultInfiniteProgressIndicatorSize = 20.dp

    @Composable
    fun progressIndicatorColors(
        foregroundColor: Color = MiuixTheme.colorScheme.primary,
        disabledForegroundColor: Color = MiuixTheme.colorScheme.disabledPrimarySlider,
        backgroundColor: Color = MiuixTheme.colorScheme.secondaryContainer,
    ): ProgressIndicatorColors
}
```

### Surface
```kotlin
@Composable
fun Surface(
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    color: Color = MiuixTheme.colorScheme.surface,
    contentColor: Color = MiuixTheme.colorScheme.onSurface,
    border: BorderStroke? = null,
    shadowElevation: Dp = 0.dp,
    content: @Composable () -> Unit,
)

@Composable
fun Surface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RectangleShape,
    color: Color = MiuixTheme.colorScheme.surface,
    contentColor: Color = MiuixTheme.colorScheme.onSurface,
    border: BorderStroke? = null,
    shadowElevation: Dp = 0.dp,
    interactionSource: MutableInteractionSource? = null,
    indication: Indication? = LocalIndication.current,
    content: @Composable () -> Unit,
)
```

### Divider
```kotlin
@Composable @NonRestartableComposable
fun HorizontalDivider(
    modifier: Modifier = Modifier,
    thickness: Dp = DividerDefaults.Thickness,
    color: Color = DividerDefaults.DividerColor,
)

@Composable @NonRestartableComposable
fun VerticalDivider(
    modifier: Modifier = Modifier,
    thickness: Dp = DividerDefaults.Thickness,
    color: Color = DividerDefaults.DividerColor,
)

object DividerDefaults {
    val Thickness = 0.75.dp
    val DividerColor: Color @Composable get() = MiuixTheme.colorScheme.dividerLine
}
```
There is **no** `Divider()` alias — the names are `HorizontalDivider` / `VerticalDivider`.
`DividerDefaults.DividerColor` is a `@Composable` getter (unusual but real).

### Extras you may want
- `SmallTitle(text, modifier, textColor, insideMargin)` — section header; `SmallTitleDefaults.InsideMargin = PaddingValues(28.dp, 8.dp)`.
- `SnackbarHost(state: SnackbarHostState, modifier, content = { Snackbar(it) })`,
  `SnackbarHostState.showSnackbar(message, actionLabel = null, withDismissAction = false, duration)`.
- `FloatingActionButton`, `FloatingToolbar`, `NavigationRail`, `TabRow`, `Slider`, `Checkbox`,
  `RadioButton`, `SearchBar`, `PullToRefresh`, `ScrollBar` (opt-in, see §9),
  and `top.yukonga.miuix.kmp.extra.*` (`SuperSwitch`, `SuperDialog`, `SuperDropdown`, …).

---

## 8. Minimal copy-pasteable example

Verified: this exact file compiles with kotlinc 2.3.20 + Compose plugin against
`miuix.aar` 0.8.8 (with `miuix-icons` 0.8.8 on the classpath for the `extended` import variant —
see the note after the snippet).

```kotlin
package com.local.embylite

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.basic.ArrowUpDown
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.basic.Search
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

@Composable
fun Screen() {
    // 1) Theme: remember the controller so state survives recomposition.
    val controller = remember { ThemeController() }   // ColorSchemeMode.System by default
    MiuixTheme(controller = controller) {

        // 2) Nav model
        val tabs = remember {
            listOf(
                NavigationItem("Home", MiuixIcons.Basic.Check),
                NavigationItem("Files", MiuixIcons.Basic.Search),
                NavigationItem("Log", MiuixIcons.Basic.ArrowRight),
                NavigationItem("Settings", MiuixIcons.Basic.ArrowUpDown),
            )
        }
        var selected by remember { mutableIntStateOf(0) }
        var wifi by remember { mutableStateOf(true) }
        val scrollBehavior = MiuixScrollBehavior()

        // 3) Scaffold with small top bar + bottom navigation bar
        Scaffold(
            topBar = {
                SmallTopAppBar(
                    title = "Embylite",
                    scrollBehavior = scrollBehavior,
                    actions = {
                        IconButton(onClick = { /* ... */ }) {
                            Icon(
                                imageVector = MiuixIcons.Basic.Check,
                                contentDescription = "Done",
                                tint = MiuixTheme.colorScheme.onSurface,
                            )
                        }
                    },
                )
            },
            bottomBar = {
                NavigationBar {
                    tabs.forEachIndexed { index, item ->
                        NavigationBarItem(
                            selected = selected == index,
                            onClick = { selected = index },
                            icon = item.icon,
                            label = item.label,
                        )
                    }
                }
            },
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .padding(innerPadding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 4) Card with Text inside
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        insideMargin = PaddingValues(16.dp),
                        colors = CardDefaults.defaultColors(),
                    ) {
                        Text(
                            text = "Hello Miuix",
                            fontSize = 19.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MiuixTheme.colorScheme.onSurfaceContainer,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "body1 style",
                            style = MiuixTheme.textStyles.body1,
                        )
                    }
                }
                // 5) Switch row
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(text = "Wi-Fi")
                            Switch(checked = wifi, onCheckedChange = { wifi = it })
                        }
                    }
                }
            }
        }
    }
}
```

Wiring it into the activity (same shape as the current `MainActivity.kt:38-45`):

```kotlin
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.remember
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

setContent {
    // ⚠️ isSystemInDarkTheme() is @Composable — read it OUTSIDE remember { }.
    // Putting it inside remember { } is a compile error:
    //   "@Composable invocations can only happen from the context of a @Composable function"
    val dark = isSystemInDarkTheme()
    val controller = remember(dark) {
        ThemeController(
            colorSchemeMode = if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light,
        )
    }
    MiuixTheme(controller = controller) {
        Screen()
    }
}
```

(If you prefer, the simpler equivalent that `MainActivity.kt` already uses today avoids
`ThemeController` entirely:
`MiuixTheme(colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) { … }`.)

> **Icon caveat for the snippet above.** It uses the four bundled `MiuixIcons.Basic.*` icons, so it
> needs **no** extra dependency. If you switch to extended icons (`MiuixIcons.Settings`,
> `MiuixIcons.Delete`, …), first add
> `implementation("top.yukonga.miuix.kmp:miuix-icons:0.8.8")` and import e.g.
> `top.yukonga.miuix.kmp.icon.extended.Settings`.

---

## 9. Opt-in / experimental annotations

- **There is no `ExperimentalMiuixApi` annotation in 0.8.8.** I searched the whole
  `classes.jar` — the only `@RequiresOptIn` annotation present is
  `top.yukonga.miuix.kmp.interfaces.ExperimentalScrollBarApi` (source:
  `miuix/src/commonMain/.../interfaces/ExperimentalScrollBarApi.kt`):

  ```kotlin
  @RequiresOptIn(
      message = "This ScrollBar API is experimental. It has known issues and may change without notice.",
      level = RequiresOptIn.Level.WARNING,
  )
  @Retention(AnnotationRetention.BINARY)
  @Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
  annotation class ExperimentalScrollBarApi
  ```

  It is applied to `ScrollBar`, `VerticalScrollBar`, `HorizontalScrollBar`,
  `rememberScrollBarAdapter`, … Only add
  `@OptIn(ExperimentalScrollBarApi::class)` if you use those.

- Everything covered in §1–§8 (theme, Scaffold, NavigationBar, TopAppBar, Card, Text, Switch,
  Button, IconButton, Icon, TextField, ProgressIndicator, Surface, Divider) needs **no** opt-in —
  verified by compiling them with no `@OptIn` at all.

- `TextField(state = …)` uses `androidx.compose.foundation.text.input.TextFieldState`, which is
  stable in Compose Foundation 1.10.x — no opt-in required.

- If you ever pass `smoothRounding = false`, nothing changes API-wise; it just switches to plain
  `RoundedCornerShape` (better HWUI performance on low-end devices).

---

## 10. minSdk / platform caveats

- The published AAR manifest declares `android:minSdkVersion="23"`; the project's `minSdk 26` is
  comfortably above it. No manifest merge conflict.
- **Monet / dynamic color:** `ColorSchemeMode.MonetSystem|MonetLight|MonetDark` calls
  `platformDynamicColors()` (`miuix/src/androidMain/.../DynamicColors.android.kt`), which:
  - on **API 31+ (S)** reads `android.R.color.system_accent*` roles;
  - on **API 33+ (TIRAMISU)** reads `Settings.Secure "theme_customization_overlay_packages"`;
  - on **API 36+** uses `ThemeColorSpec.Spec2025`;
  - below API 31 falls back to `monetSystemColors(dark)`.
  All paths are guarded with `Build.VERSION.SDK_INT` checks, so **minSdk 26 is safe** — on API 26–30
  you just get the static fallback palette instead of system-derived colors. If you want
  HyperOS-consistent colors on all devices, use `ColorSchemeMode.System/Light/Dark` with
  `lightColorScheme()`/`darkColorScheme()` (what `MainActivity.kt` does today) or set an explicit
  `keyColor`.
- Runtime deps pulled in automatically: `navigationevent-compose 1.0.1`,
  `material3-window-size-class 1.9.0`, `io.github.kyant0:shapes 1.2.0`,
  `material-color-utilities 4.1.1` (MaterialKolor). The last two are the ones that do the
  HCT/palette math — no extra Gradle work needed, but they do add ~method count.
- Compose version alignment: `miuix` 0.8.8 is built against **Compose Multiplatform 1.10.3** and
  **Kotlin 2.3.20** — exactly the project's versions, so no `compose-compiler` mismatch.
  The Gradle cache also contains `androidx.compose.*` 1.12.0 artifacts (from another project);
  the compile check above used those and still resolved cleanly, but the supported combination is
  1.10.3.

---

## 11. Exact import paths (quick reference)

```kotlin
// theme
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.TextStyles
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.defaultTextStyles
import top.yukonga.miuix.kmp.theme.LocalContentColor

// layout / navigation
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.FabPosition
import top.yukonga.miuix.kmp.basic.ToolbarPosition
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationBarDisplayMode
import top.yukonga.miuix.kmp.basic.NavigationBarDefaults
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.NavigationRail
import top.yukonga.miuix.kmp.basic.NavigationRailItem
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem

// app bars
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.TopAppBarDefaults
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.basic.TopAppBarState

// content
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.CardColors
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.VerticalDivider
import top.yukonga.miuix.kmp.basic.DividerDefaults

// controls
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.SwitchDefaults
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.IconButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextFieldDefaults
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState

// utils
import top.yukonga.miuix.kmp.utils.PressFeedbackType   // None | Sink | Tilt

// icons — bundled (no extra artifact)
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.basic.Search
import top.yukonga.miuix.kmp.icon.basic.SearchCleanup
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.basic.ArrowUpDown

// icons — extended (needs `top.yukonga.miuix.kmp:miuix-icons:0.8.8`)
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Play
// … then use MiuixIcons.Settings / MiuixIcons.Delete / MiuixIcons.Play
//    or MiuixIcons.Light.Settings / MiuixIcons.Regular.Settings / MiuixIcons.Heavy.Settings
```

Extended icon names available in 0.8.8 (155 total, `top.yukonga.miuix.kmp.icon.extended`):

```
Add AddCircle AddFolder Alarm Album All Answer AppRecording Back Background Backup
BankCards Blocklist CallRecording Carrier ChevronBackward ChevronForward Clear Close
Close2 CloudFill Community Contacts ContactsBook ContactsCircle ConvertFile Copy Create
Cut Delete Download Edit Email ExpandLess ExpandMore Favorites FavoritesFill File
FileDownloads Filter Folder FolderFill Forward GridView Help Hide HorizontalSplit Image
Import Info Layers Link ListView Location Lock MapAlbum Merge Messages Mic MicSlash
MindMap Months More MoreCircle MoveFile Music Notes NotesFill Ok Paste Pause Phone
Photos Pin Play Playlist Promotions Recent Recording RecordingTape Redo Refresh Remove
RemoveContact Rename Replace Reply ReplyAll Report Reset RotateLeft Scan ScreenCapture
ScreenMirroring Search SearchDevice SelectAll Send Settings Share Show Sidebar Sort
Stopwatch Store Tasks Th1…Th31 Theme Timer TopDownloads Translate Trim Tune Undo Unlock
Unpin Update UploadCloud VerticalSplit VolumeOff VolumeUp Weeks WorldClock Years ZoomOut
```

---

## 12. Things that do NOT exist (verified absent — do not use)

| Guessed name | Reality in 0.8.8 |
| --- | --- |
| `CardDefaults.cardColors()` | → `CardDefaults.defaultColors(color, contentColor)` |
| `MiuixIcons.Useful.*` | no such object; only `Basic`, `Light`, `Regular`, `Heavy` |
| `ExperimentalMiuixApi` | does not exist anywhere in the AAR |
| `Divider()` | → `HorizontalDivider()` / `VerticalDivider()` |
| `SmallTopAppBar(title = { … })` composable slot | `title` is a `String` |
| `NavigationBarItem(icon = { … }, label = { … })` slots | `icon: ImageVector`, `label: String` |
| `MiuixTheme(darkTheme = …)` | use `ThemeController(colorSchemeMode = …)` or `colors =` |
| `MiuixTheme.colorScheme` outside `@Composable` | it is a `@Composable @ReadOnlyComposable` getter |
| material3 `Scaffold`/`TopAppBarScrollBehavior` reuse | Miuix has its own `Scaffold`, `ScrollBehavior` |
| `CardDefaults.cardColors`, `ButtonDefaults.textButtonColors()` without parens | the latter is a function, needs `()` |
