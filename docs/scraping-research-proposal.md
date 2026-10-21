# 本地视频元数据（NFO + 海报）自动刮削 —— 调研报告与立项方案

> 项目：Embylite（原 LocalEmby）· 阶段：仅调研与立项，不涉及代码修改
> 日期：2026-10-21 · 调研范围：开源刮削项目、刮削数据源、可集成依赖库

---

## 1. 背景与目标

Embylite 目前已具备：

- `EpisodeIdentity` / `Item`：基于父目录 + 文件名的剧集身份解析（SxxExx、第N集、EPxx 等）
- `MetadataStore`：元数据覆盖层，支持通过 `/api/dev/metadata` 注入标题、简介、年份、评分、演职人员、海报
- `ThumbProvider`：视频抽帧缩略图
- `DanmuProxy` + `EpisodeMatchPolicy`：第三方弹幕源搜索 + 候选二次校验 + 指纹缓存（成熟的"外部数据源绑定"范式）

**痛点**：元数据目前只能手动注入，海报多为抽帧占位图。需要**自动刮削**：为本地视频生成 Kodi 兼容的 info 文件（`movie.nfo` / `tvshow.nfo`）并下载海报（`poster.jpg` / `fanart.jpg`）保存到本地，供 Embylite 自身与 Kodi/其他媒体中心复用。

**技术约束**（决定选型方向）：

- Android 原生 App，Java/Kotlin，minSdk 26
- APK 目前约 7MB，对体积敏感，倾向零/极少新依赖
- 已有 OkHttp 网络层（`DanmuNetwork`，含 DoH 兜底）
- 媒体目录访问走 SAF / `MANAGE_EXTERNAL_STORAGE`
- 现有回归测试模式为"纯 Java、无设备"（`tests/EpisodeRegression.java`）

---

## 2. 需求定义

| 维度 | 内容 |
|---|---|
| 刮削对象 | 电影、剧集（剧 → 季 → 集）、可选动漫命名风格 |
| 产出物 | Kodi 兼容 NFO（XML）、poster/fanart 等图片，写入视频所在目录或应用私有目录 |
| 媒体格式 | mkv / mp4 / avi / ts / m2ts / wmv / mov 等常见容器（刮削只依赖文件名与目录结构；技术信息探测用系统 `MediaMetadataRetriever` 即可覆盖常见格式） |
| 数据源 | TMDB（首选，中文支持好）、可选豆瓣；图片源 TMDB / fanart.tv |
| 集成形态 | 嵌入现有 App：扫描后自动刮削 + 识别页人工确认兜底 + 指纹缓存 |
| 合规 | 开源非商业分发 → 数据源条款需满足"非商业 + 署名"，或采用用户自填 Key 模式 |

---

## 3. 开源项目横向对比

### 3.1 桌面/服务端刮削工具（不可直接嵌入，作参考实现）

| 项目 | 语言/形态 | 核心能力 | 数据源 | 许可证 | Android 可集成性 |
|---|---|---|---|---|---|
| **TinyMediaManager** | Java/Swing 桌面 | NFO 读写、海报下载、重命名；电影/剧集/电影集 | TMDB、IMDb、TVDB、fanart.tv、Trakt | 源码 Apache-2.0，但 **v4 起 EULA 禁止再分发 + 订阅制**（license 模块与 launcher 闭源） | ❌ 桌面 GUI，EULA 阻碍二次分发；逻辑可参考 |
| **MediaElch** | C++/Qt 桌面 | Kodi NFO 生成、fanart.tv 海报、多刮削器 | TMDB、TVDB、IMDb、fanart.tv、OFDb | **LGPL-3.0**，活跃维护（2.12.0，2024-10） | ❌ C++/Qt 无法嵌入；**NFO/刮削流程的最佳参考实现** |
| **FileBot** | Java 桌面 | 重命名、元数据、字幕 | TMDB、TVDB、AniDB 等 | **商业许可**（部分版本曾为闭源试用） | ❌ 许可证不允许嵌入分发 |
| **Kodi scraper 体系** | XML + Python 插件 | Kodi 官方刮削器（universal scraper 等） | TMDB、TVDB、fanart.tv、IMDb | GPL（随 Kodi） | ❌ 依赖 Kodi 运行时；**NFO 格式规范的事实标准** |
| **Jellyfin** | C# 服务端 | 完整元数据提供器架构（NFO 插件、TMDB 插件） | TMDB、TVDB、fanart.tv | GPL-2.0（部分插件 MIT） | ❌ 服务端；**刮削器抽象/回退策略的参考** |
| **MoviePilot / nas-tools** | Python 服务端 | 中文场景刮削、豆瓣+TMDB 双源 | TMDB、豆瓣 | GPL-3.0 | ❌ Python；**豆瓣源与中文识别规则的参考** |

**结论**：这一类项目**没有能直接嵌入 Android 的**，价值在于参考其 NFO 格式、刮削器抽象、匹配与回退策略。

### 3.2 可嵌入 Android/Java 的库

| 库 | 作用 | 许可证 | 维护状态 | 评估 |
|---|---|---|---|---|
| **tmdb-java**（UweTrottmann） | TMDB v3 API 的 Retrofit 封装，电影/剧集/图片全覆盖，官方文档列为推荐 wrapper | **Apache-2.0** | 活跃（v2.13.0，2025-10；Java 8 字节码，Android 友好） | ✅ 可直接引入；代价是带入 Retrofit + Gson/Gson-convertor 依赖 |
| **anitomyJ**（Vorror） | Anitomy 的纯 Java 移植，解析动漫文件名（字幕组、标题、集号、分辨率等） | **MPL-2.0** | **停更（2016 年）**，需 Apache Commons | ⚠️ 逻辑优秀但项目陈旧；建议只参考其规则，不引入依赖 |
| **Enzyme（python-enzyme）** | Kodi NFO 解析 | MIT | Python 库 | ❌ 语言不通；Android 内置 `XmlPullParser`/`XmlSerializer` 零依赖即可完成 NFO 读写 |
| **guessit** | 文件名解析（最强规则库） | LGPL-3.0 | Python | ❌ 不可嵌入；Embylite 已有 `EpisodeIdentity`，可补齐其规则 |
| **ffmpeg-kit / MediaInfoLib** | 技术元数据（编码、分辨率、时长） | ffmpeg-kit 已于 2025-01 停更（GPL/LGPL 变体）；MediaInfoLib 为 BSD-2（有 Android 支持） | — | ⚠️ Embylite 已有探测逻辑且刮削不强依赖技术信息，**本期不需要** |

### 3.3 刮削数据源对比

| 数据源 | 内容覆盖 | 中文支持 | 费用/条款 | 评估 |
|---|---|---|---|---|
| **TMDB** | 电影+剧集+人物+图片，最全 | ✅ 官方多语言（zh-CN 翻译数据好） | 非商业免费，需在"关于"页放置 TMDB logo 与声明 *"This product uses the TMDB API but is not endorsed or certified by TMDB."*；商业化需签商业协议 | ✅ **首选**。注意：App 若未来收费，按 TMDB 官方答复即构成商业用途，需商业协议或改为用户自填 Key 模式 |
| **TheTVDB** | 剧集最强（历史数据） | 一般 | v4 密钥**按项目发放**；年收入 < $50k 免费+署名；商业 $1k/年起；或终端用户订阅 PIN 模式 | ⚠️ 条款繁琐，个人项目可用但申请门槛高于 TMDB |
| **fanart.tv** | 高质量同人海报/背景 | — | 免费 API key | ✅ 图片补充源（二期可选） |
| **IMDb** | 标准评分源 | — | **无公开 API** | ❌ 只能离线数据集或网页抓取，不考虑 |
| **豆瓣** | 中文影视、中文简介评分最强 | ✅ 最强 | **无官方 API**，网页/移动接口均非官方，存在反爬与合规风险（MoviePilot 等也是非官方接入） | ⚠️ 合规灰色。建议做成可选、默认关闭的扩展源，不内置抓取逻辑到核心 |
| **弹弹play**（已接） | 弹幕 + 附带剧集元数据 | ✅ | 兼容 API，已有集成 | ✅ 可顺手利用其匹配结果作为剧集命中的旁证 |

---

## 4. 适配性评估（对照 Embylite 现状）

| 需求点 | 现状 | 缺口 |
|---|---|---|
| 剧名/季/集识别 | `EpisodeIdentity` 已覆盖主流命名 | 需补齐动漫命名（`[字幕组] 标题 [01][1080p]`）规则，可抄 anitomyJ/TMM 的正则思路 |
| 搜索关键词生成 | 有"父目录 + 文件名"清洗逻辑 | 需增加"净化标题"步骤（去发行组/分辨率/音轨标记），提高 TMDB 命中率 |
| 候选二次校验 | `EpisodeMatchPolicy` 范式成熟 | 复用同思路对 TMDB 搜索结果做"标题归一化 + 年份/集数校验" |
| 元数据存储 | `MetadataStore` 覆盖层已存在 | 新增 NFO 解析作为元数据来源之一（优先级：NFO > 手动注入 > 占位） |
| 图片存储 | `ThumbProvider` 抽帧 | 新增海报下载；本地无网时回退抽帧 |
| 写入视频目录 | SAF / 全盘权限已具备 | SAF 下创建 `.nfo`/`poster.jpg` 需走 `DocumentFile`；失败时回退应用私有目录 |
| 体积 | 7MB，敏感 | **推荐零新依赖**（见下），不引入 tmdb-java 全家桶 |
| 人工兜底 | 弹幕页已有"候选确认"交互 | 复用该交互做"刮削确认/换一个" |

**总体判断**：Embylite 的"文件名解析 → 外部源搜索 → 候选校验 → 指纹缓存 → 人工确认"整条链路与弹幕识别完全同构，刮削模块是这条链路的第二实例，**自研轻量实现的边际成本很低，且没有现成开源库可以整体替换**。

---

## 5. 推荐方案

**自研轻量刮削模块（`Scraper` 系），数据源一期只接 TMDB，产出 Kodi 兼容 NFO + 海报，零新增第三方依赖。**

理由：

1. 没有可直接嵌入 Android 的开源刮削库（见 §3），tmdb-java 虽可用但为省体积、且 Embylite 已有 OkHttp 与 JSON 处理，直接调 TMDB REST 仅需数百行；
2. 文件名解析与候选校验两大难点已有自研沉淀（`EpisodeIdentity` / `EpisodeMatchPolicy`），外购不如复用；
3. Kodi NFO 是 XML 事实标准，`XmlPullParser`/`XmlSerializer` 系统自带，写入后 Kodi、TMM、MediaElch 均可识别，生态兼容免费获得；
4. 许可证干净：自研 + 仅运行时调 TMDB API（非商业 + 署名），无 GPL 传染、无 EULA 风险。

**备选**：若想加快一期进度，可引入 `tmdb-java`（Apache-2.0）换取类型安全的 API 封装，接受 Retrofit 依赖（约 +1MB）。作为 A/B 记录在案，默认不采用。

### 目录产物约定（Kodi 兼容）

```
电影/
  流浪地球2 (2023)/
    流浪地球2 (2023).mkv
    movie.nfo            # <movie> XML：标题/简介/年份/评分/演职人员/uniqueid(tmdb)
    poster.jpg
    fanart.jpg           # 可选
剧集/
  某剧 (2020)/
    tvshow.nfo           # <tvshow> XML
    poster.jpg
    Season 01/
      某剧 S01E03.mkv
      某剧 S01E03.nfo    # <episodedetails>（可选，一期可只做剧集级）
```

读取优先级：同名 NFO（`movie.nfo` / `tvshow.nfo` / `<视频文件名>.nfo`）→ `MetadataStore` 手动注入 → 抽帧占位。

---

## 6. 集成思路

```
VideoLibrary 扫描
      │
      ▼
已有 NFO？ ──是──▶ 解析 NFO ──▶ MetadataStore 覆盖层
      │否
      ▼
EpisodeIdentity / 标题净化（新增：去字幕组、分辨率、音轨等噪音）
      │
      ▼
TMDB 搜索（OkHttp 复用 DanmuNetwork，用户在设置页填自己的 API Key）
      │  /search/movie  /search/tv（language=zh-CN）
      ▼
候选校验（复用 EpisodeMatchPolicy 思路：标题归一化 + 年份 + 季集数）
      │
      ├─ 唯一高置信命中 ──▶ 自动刮削
      └─ 多候选/低置信 ──▶ 识别页"待确认"列表（复用弹幕页交互）
      │
      ▼
详情拉取（/movie/{id}?append_to_response=credits  language=zh-CN）
      │
      ▼
写本地：NFO（XmlSerializer）+ poster/fanart 下载（SAF 写视频目录；失败回退应用私有目录）
      │
      ▼
指纹缓存（路径 + 大小 + 修改时间 + 数据源，模式同弹幕绑定缓存；文件变化自动重刮）
```

关键设计点：

- **Key 模式**：设置页新增 TMDB API Key 输入（BYO-key），与弹幕源地址同级；同时内置默认 Key 方便开箱即用，并在"关于"中放置 TMDB 署名声明
- **合规署名**：TMDB 要求的 logo 与声明文案放"关于/设置"页
- **流量节制**：详情与图片结果落盘，指纹未变化不重复请求；尊重 TMDB 软限速（缓存优先）
- **失败降级**：无网/无 Key → 跳过刮削，仍用抽帧；SAF 写入失败 → 应用私有目录（仅 Embylite 自用）
- **测试**：NFO 解析/生成、标题净化、候选校验全部写成纯 Java 回归（沿用 `EpisodeRegression` 模式，无设备可跑）

---

## 7. 实施步骤（立项计划）

| 阶段 | 内容 | 交付物 | 依赖 |
|---|---|---|---|
| M1 | NFO 模型与读写：`<movie>/<tvshow>/<episodedetails>` 解析与生成 | `NfoParser.java` / `NfoWriter.java` + 纯 Java 回归测试 | 无 |
| M2 | TMDB 客户端：搜索 + 详情 + 图片 URL 拼接（language=zh-CN） | `TmdbClient.java`（复用 DanmuNetwork） | 设置页 Key 配置 |
| M3 | 识别与校验：标题净化、候选校验、置信分级 | `TitleCleaner.java`、`ScrapeMatchPolicy.java` + 回归测试 | EpisodeIdentity 复用 |
| M4 | 落盘：SAF 写 NFO/海报 + 私有目录回退 + 指纹缓存 | `ScraperStore.java` | M1–M3 |
| M5 | 接线与 UI：扫描流程触发、MetadataStore 读 NFO、设置页（Key/开关）、识别页确认候选 | 完整功能 | M4 |
| M6 | 文档与合规：README 更新、关于页 TMDB 署名、免责声明补充 | 发布就绪 | M5 |

里程碑建议：M1+M3（纯逻辑、可离线验证）先行；M2+M4（网络与存储）随后；M5 打通端到端。

---

## 8. 风险与开放问题

1. **TMDB 商业边界**：项目当前免费开源属非商业；若未来收费，需按 TMDB 政策申请商业协议或全面转 BYO-key（用户自担）。BYO-key 为主、内置 Key 为辅可对冲。
2. **豆瓣源合规**：无官方 API，一期不做；二期若做，以"可选插件、默认关闭、用户自担"原则落地。
3. **SAF 写入兼容性**：不同 ROM 对 `DocumentsProvider` 行为有差异（尤其外置存储/OTG），需在 M4 做真机矩阵测试；写失败一律回退私有目录，不阻塞主流程。
4. **动漫命名识别率**：`EpisodeIdentity` 对 `[字幕组]` 风格覆盖有限，M3 需要补规则并建用例集（可参考 anitomyJ 的元素分类与 TMM 的 `anime` 刮削策略）。
5. **搜索歧义**（同名电影、翻拍）：低置信一律进人工确认，不做静默猜测——与现有弹幕绑定哲学一致。

---

## 9. 结论

- **没有现成可嵌入 Android 的开源刮削库**；桌面工具（TMM/MediaElch）因语言、GUI 与许可证原因只能作参考。
- **推荐立项：自研轻量刮削模块 + TMDB 单源 + Kodi NFO 本地落盘 + 零新依赖**，复用现有识别/校验/缓存/确认四件套，边际成本最低、许可证最干净。
- 二期扩展方向：fanart.tv 图片源、豆瓣可选源、剧集级 NFO、MoreProps（编码等技术信息写入 NFO）。
