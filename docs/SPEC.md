# Spec：Comic-Viewer MVP（安卓漫画阅读器）

> 权威版本：`docs/SPEC.md`（索引与全局口径）+ `docs/spec/` 分册，两者一起构成规格。
> 跟踪面：GitHub Issue #1（已关闭）。两者同步更新。
> 本文件只记**决定与约束**：实现细节看代码与 KDoc，逐条溯源看 git 历史与各票 issue，
> 术语以仓库根 `GLOSSARY.md` 为准。

## 分册索引

> **读法**：只读与当前改动相关的册，不要整批读。故事编号全局连续（1–54）、**是稳定锚点**：
> 删除的故事留空号、不重排（代码注释里的「spec 故事 N」据此长期有效）；新增故事一律在末尾追加新编号。

| 分册 | 覆盖 | 故事 | 体量 |
|---|---|---|---|
| `docs/spec/sources.md` | 来源与连接：增删改连接、凭据、Komga 集成（含分页取数上限、路径选择器按需加载）、文件源、CBZ/ZIP、SMB/WebDAV 表单、连接名、会话来源复用与释放、SMB 会话上的读（票 #113） | 1–8 | 12 KB |
| `docs/spec/browsing.md` | 浏览与列表：目录列表与排序、列表快照与按需加载首帧（票 #119 步骤 3）、枚举性能、首屏与封面后到、条目呈现、封面去网点（预模糊）、浏览层级路径菜单 | 9–22、55 | 33 KB |
| `docs/spec/reader.md` | 阅读器：两种阅读模式、阅读页缓存、书与容器、阅读菜单与预览条、进度规则 | 23–42 | 11 KB |
| `docs/spec/startup.md` | 书柜、启动与设置：书柜/启动/设置的故事、启动中转与退化 | 43–54 | 6 KB |
| `docs/spec/shell.md` | UI 骨架：快速定位滑条、顶栏标题路径菜单、返回逐级、导航过渡、窗口与系统栏、名称与图标 | — | 17 KB |

跨册落位：**列表快照**与按需加载首帧在 `docs/spec/browsing.md`；**来源**与**连接**在 `docs/spec/sources.md`；词表本体永远是仓库根 `GLOSSARY.md`。

## Problem Statement

我在 Windows 上把漫画库整理好（目录命名、排序都符合 Windows 资源管理器规则），
再 rsync 到 Linux NAS。

在安卓设备上至今没有一款完全符合我使用习惯的漫画阅读器：

- 浏览 NAS（SMB/WebDAV）、连 Komga、条漫阅读、蓝牙鼠标操作、精确到页的跳转预览、
  与 Windows 一致的排序——这些散落在不同 APP 里，或行为对不上
  （尤其是排序规则与封面/进度呈现）
- Perfect Viewer 最接近但闭源且若干交互不可定制

## Solution

Comic-Viewer：一款按我的习惯定制的安卓原生漫画阅读器。

- **四来源统一浏览**：本地存储（SAF）、SMB、WebDAV、Komga，同一套浏览/排序/阅读体验
- **两种阅读模式**：条漫（垂直连续滚动）与单页（LTR/RTL）
- **蓝牙鼠标全支持**：滚轮滚动/翻页、左右键、后退/前进侧键
- **Windows 一致的名称自然排序**与全来源通用的时间排序（修改时间/发布时间）
- **Perfect Viewer 风格交互**：触摸区域类型 3（纵向三等分）、双击放大、
  跳页横向预览条（全书页滑动）、上一本/下一本
- **进度管理**：本地持久化 + Komga 双向同步；
  列表进度条（绿 = 进行中、红满格 = 读完）
- **书柜按连接分柜**，陈列每个连接的根条目，封面自动取首页图（细节见故事 43/44）；
  没有逐本「加入书柜」的动作

技术底座：Kotlin + Jetpack Compose，minSdk 26。

## Implementation Decisions

### 技术栈

Kotlin + Jetpack Compose，minSdk 26（Android 8.0），
侧载分发（SAF 而非全盘存储权限与商店审核策略一致）

### Source 统一接口（核心架构 seam）

- 四种来源各实现一个适配器
- 接口能力 = 列出条目（含排序参数）、打开书（取得页清单）、按页取图、进度读写、
  **顶层条目**（`topLevelEntries` / `cachedTopLevelEntries`：路径菜单要列的一级目录）
  - 默认 = 根容器（`containerId = null`）那一层；**Komga 覆盖为固定的四入口**
    （收藏 / 系列 / 书籍 / 阅读过），与连接的起始路径无关；起始路径正好落在某一类本身时，
    那一项在返回值里标 `TopLevelEntry.isStartLayer`（菜单据此把它指回起点层）
- 来源差异全部压在适配器内
- 按页取一组的**上限**（`maxPageSize`）：调用方按「还差多少条」给 `size`、再夹到本上限；
  默认无上限，Komga 覆盖为服务器页上限
  - 有了上限，调用方才能把首屏需要的那几页并成**一次**枚举
  - 前置条件：本值不得小于调用方的一页长度，否则夹不住

**空书口径（四来源一致）**：

- 书存在但一页都没有（压缩包内没有图片、Komga 返回空页列表）时
  返回 `pageCount == 0` 的句柄，界面给中文空态
- 抛 `IllegalArgumentException` 只留给「不是一本书」的输入
  （id 形状/前缀不对、越界引用、目录本层没有图片）

### 持久化

Room 数据库——连接配置、阅读进度、浏览历史、最近阅读。

**凭据加密存储**：

- SMB/WebDAV 的密码、Komga 的 API Key/密码经 Android Keystore 的
  AES-256-GCM 加密后落库
- 形如 `enc:v1:<Base64(IV‖密文+tag)>`
- 密钥为 app 级不可导出条目，不硬编码、不随包分发
- v4→v5 迁移把存量明文改写为密文，读路径仍认旧明文，解密失败提示重新填写

### 图片解码

- 支持 jpg/jpeg、png、webp、gif（首帧静态）
- 大图子采样/分块解码防 OOM

## Testing Decisions

- **只测外部行为，不测实现细节**：测试通过公共接口断言可观察结果
- **Seam ①（主）：Source 统一接口**
  - 契约直接盯 `FsBackend` seam 的文件树后端 adapter（共 4 个：本地 SAF / SMB / WebDAV / File），
    其中三个有绑定，三者跑的是同一份 `DocumentTreeSource` 代码（浏览条目 → 排序 → 打开书 → 按页取图 → 进度读写）
  - 本地绑定 = 生产用的 `SafBackend` + 假文档提供者（Robolectric）
  - SMB / WebDAV 绑定 = 各自传输层用文件系统伪装（`FakeSmbTransport` / `FakeWebDavTransport`）
  - `File` 后端（`main` 里零调用点）不进契约绑定，只在纯 JVM 用例里出现
  - `Source` seam 的这套契约只覆盖文件源；Komga 的 REST 语义另有 `KomgaSourceTest` / `HttpKomgaApiTest`
- **Seam ②（辅）：名称自然排序比较器**
  - 纯函数测试，入库黄金样本一律用**合成名**
    （仓库是 PUBLIC，真实书库名不进仓库）
  - 守护 Windows 兼容硬约束：`第2话`<`第10话`、符号/数字/字母/假名优先级、
    大小写、假名五十音序、中文拼音、GB2312 表外字归汉字段
  - **真实书库顺序由本地探针**
    读 `references/name-order-expected.txt`（不入库）逐条比对，
    给出差异表并按「位次不一致/反序对」棘轮基线报警
    （基线常量及其重标要求写在测试里）
  - 文件不存在（干净检出）时**数据类测试 skipped**
- **绿地无先例**：以上两个 seam 即测试基线的起点，后续功能测试优先复用这两个口
- **UI 层**：Compose 交互不做大规模自动化，走手动验收清单
  （触摸区域、缩放、鼠标事件、预览）
- **反射读界面内部状态的白名单：当前为空**（与本节「只测外部行为」的取向一致）
  - 已点名的相邻用例：`ui/session/SessionStateTest` 的 `对外面恰为清单里那几个成员` 读 `declaredMethods`——
    读的是 `SessionState` 的**公开 API 面**（类对外成员的守护，见下面「会话状态模块」那一条），因此**不进**本白名单
  - 顶栏的行数上限与省略口径靠**真实高度不变式**守护（名字再长顶栏高度不变）
  - 但省略号的具体取值在本机量不出（Robolectric 不按宽度换行）⇒ **靠设备目视**
  - 将来若再出现「必须读组合内部状态」的用例，先在本节登记，再决定是否抽公共夹具
- **位置模块（`ui/BrowseScrollPosition.kt`）对外只有 6 个入口**：组合期进屏「上次第几条」、
  首屏 effect 进屏「当下读数 / 本次取数下限」、离开记一次、真正离屏时结束进屏会话、开机交回落位层、
  位置已放回。步骤层（登记进屏基准、读写记录、启动收口等）一律 `private` ⇒ **步骤层不设 seam**，
  测试与生产穿过同一批入口（守护用例：`BrowseScrollPositionTest.对外面只有六个入口 步骤层不设 seam`）。
  - **删除清单：空**。原先直驱步骤层的观察点都能改成入口观察（跨屏 = 入口 → `endEntrySession` → 入口；
    「盘上那条」与「内存那条」的分辨 = 换一份干净模块读同一份存储），36 个用例逐条对上，
    断言数值与预期不变，观察点改走对外入口（只多了上面那条守护用例）。
  - 为什么收窄：步骤层那批成员生产调用点为 0、只被单测直驱 ⇒ 测试穿过的 seam ≠ 调用方穿过的 seam；
    本仓测试与生产同模块，`internal` 挡不住测试，收窄只能靠 `private`。
- **会话状态模块（`ui/session/SessionState.kt`）对外只有清单里那几个成员**：会话来源槽
  （`adopt` / `clear` / `currentSource` / `currentConnId`）、浏览来源单槽（`browsingSourceFor` /
  `browsingSourceIfResolved`）、`entryNames` / `browseHistory`、会话收口 `end`。
  步骤层（槽位换出与释放、会话来源的打点与释放、清条目名缓存）一律 `private`；浏览槽「按连接释放」
  只对窄根开放（`internal`）。生产那几份依赖（协程域 / 来源构造器 / 落盘钩子）由构造参数注入，
  单测自己 `new SessionState(...)`（守护用例：`SessionStateTest.对外面恰为清单里那几个成员`）。
  实例不挂全局变量：由组合根（`MainActivity` 的 `SessionStateHolder`）持有，经 `LocalSessionState` 下发到界面。
  - 为什么收窄：会话来源槽的写入原先有四个调用点各写两行（先来源后 connId），
    顺序契约与三条副作用现在都锁在 `adopt` / `clear` 里。
- **装机包与性能取数（票 #145）**：性能、体感、功能验收**一律用 `assembleRelease` 出的包**
  - **为什么**：release 非 debuggable（系统才肯做 AOT）且打包 androidx 基线 profile
    （`assets/dexopt/baseline.prof`，装机时先按它把 Compose 那批热点方法编好）；
    debuggable 包冷启动全程解释执行 + JIT（首窗主线程 65% 时间在 CPU 上、同期与 JIT 抢代码缓存锁两万次）
    ⇒ 冷启动慢帧是前者的 8 倍、`frameMaxMs` 大 4~7 倍
  - **`debug` 包不再默认出**，只在需要**调试器 / 堆 dump** 时按需出（非 debuggable 之后那两样都没有；
    崩溃栈不在此列——未开 minify 的 release 包照样给可读栈）
  - **debug 包的 `jankPct` / `frameMaxMs` / `drawMaxMs` 不能当基线**：这三个数只在**非 debuggable + AOT** 的包上取。
    同一个数字跨包/跨版本比大小也要先确认两边都是 release + AOT；
    `jankPct` 的分母随窗口里的动画帧数变（动画本身贡献大量廉价帧）⇒ 跨「有没有动画」比比例无意义，看 `janky` **绝对值**
- **发布步骤（票 #145）**：改完代码到设备装包
  1. **版本名**：`versionName` 记 `0.1.0`（`debug` 变体自动带 `-debug` 后缀）。
     **`versionCode` 不设**（APK 里因此是 `0`）：它不随包变，诊断日志头部的 `app.version=<versionName> (<versionCode>)`
     因此**不能用来分辨包**；已装过更高版本号的设备要先卸载（或 `adb install -r -d`）才能装新包
  2. **签名**：`release` 读仓库根的 `keystore.properties`（**已 gitignore**）；keystore 本体与口令**不进版本控制**（`.gitignore` 兜住），
     也不写进本规格（本规格只记机制）。文件不存在时退回「无签名」，干净检出照旧能构建
  3. **出包**：`./gradlew assembleRelease` ⇒ `app/build/outputs/apk/release/app-release.apk`
     （没配 `keystore.properties` 的干净检出里这个包**未签名**，AGP 的产物名是 `app-release-unsigned.apk`）
  4. **装完 AOT**：`adb shell cmd package compile -m speed -f com.cc3301.comicviewer`
     （debuggable 包上这条会被压回 `verify`：回 `Success` 但等于没编）
  5. **冷启动复测**：杀进程 → 进浏览页 → 立刻快滑 5 秒，记首窗 `browseScroll` 的
     `janky` / `jankPct` / `frameMaxMs` / `drawMaxMs`
  6. **开了代码压缩（minify）之后必须重跑一次复测**：R8 改掉代码形态 ⇒ 上一版基线作废

## Out of Scope

- CBR/RAR、PDF、bmp、avif、heic/jxl 格式支持
- 压缩包内部按目录分卷阅读（CBZ 内的图片仍**全深度**收集）
- 双页拼页模式
- 阅读菜单内的返回按钮、模式切换、设置入口
  （返回 = 全面屏手势/鼠标后退侧键；模式切换与设置在设置页/抽屉）
- **OPDS（1.x 与 2.x 全不做，含下载缓存与流式阅读）**
- 前进侧键回到阅读器
- 书柜跨来源混排视图
- **逐本收藏/入柜**（书柜只按连接陈列根条目）
- 浏览滚动位置的恢复
  - **跨会话/跨进程的持久化**：**原 Out of Scope 的「滚动位置不落盘」半句已被票 #142 推翻** ——
    滚动位置**单独落盘一份**（`ui/BrowseScrollPosition.kt` 的位置模块落盘 `BrowseScrollStorage`：只存「上次停留那一层 + 该层位置」**一条**、
    **用掉即清**——「用掉」= 本进程内只喂启动那一代那一次，盘上那份留着给下一次重启；落地层上「离开这一层、
    再从上一级进来」才作废该层记录，口径见 `docs/spec/browsing.md`「滚动复位」）；
    **仍不进 `BrowseLocation`**（它保持只记目录层级，不记排序、也不记滚动位置）
  - 同一次会话内从阅读器返回与界面重建时的恢复已实现，见 `docs/spec/browsing.md` 的「浏览列表按需加载」
- 流式列表（条目先出、是不是书后到；名称与点击已不被封面阻塞，
  但列表仍在整批属性探测完成后一次返回）
- 音量键以外的键盘快捷键体系、手柄支持
- 谷歌商店分发与审核合规
- 搜索/全文检索功能

## Further Notes

- **术语**以仓库根 `GLOSSARY.md` 为准（来源、连接、连接名、书、容器、系列、收藏、
  阅读过、封面、列表快照、发布日期、条漫/单页模式、单页方向、阅读进度、页面预览、
  上一本/下一本、排序设置、名称自然排序、修改/发布时间排序、视图档位、下拉更新、
  条目名称断行、鼠标拖动滚动、快速定位滑条、触摸区域、双击放大、双指缩放、
  阅读器沉浸、列表进度条、始终从第一页打开、导航抽屉、书柜、首页、
  上次阅读/停留的位置、浏览历史等）
- 交互基准：Perfect Viewer（触摸区域类型 3、书柜结构、启动行为）；
  参考图在 `references/`（已 gitignore，不入库）
- 两个 ADR 提议（技术栈选型、Windows 排序约束）已向用户提出、暂未确认建立
- Komga 发布日期存在时区偏差的已知上游 issue（gotson/komga#818），排序用途下可接受
