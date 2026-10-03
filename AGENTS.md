# 项目记忆

## 代理技能（Agent skills）

### 工单（Issue tracker）

工单放在本仓库的 GitHub Issues（用 `gh` CLI 操作）；**规格入口** = `docs/SPEC.md`（索引 + 全局口径）+ `docs/spec/*.md`（分领域分册：`sources` / `browsing` / `reader` / `startup` / `shell`），**不往工单里贴规格**。详见 `docs/agents/issue-tracker.md`。

### 分诊标签（Triage labels）

沿用五个标准分诊标签，不自造新标签：`needs-triage`、`needs-info`、`ready-for-agent`、`ready-for-human`、`wontfix`。详见 `docs/agents/triage-labels.md`。

### 领域文档（Domain docs）

单上下文（single-context）：仓库根一个 `CONTEXT.md` + `docs/adr/`。详见 `docs/agents/domain.md`。

## 新开Issue约束

未经同意不要擅自开新票。

## 脱敏

票面、评论、提交信息、代码注释、规格里**一律不写真机现场标识**——包括：
IP（含端口）、主机名、共享名、库名、**目录名**、**书名**、连接显示名。

- 用占位：`<host>`（带端口写 `<host>:<port>`）、`<share>`、`<库名>`、`<目录名>`、`<书名>`。
- **日志不要原样粘贴**：先逐行把路径/各层名字换成占位再贴。
- 原始日志只放 `references/`（已 gitignore），不进票面、不入库。

## 文档与注释的写法

**只写事实与规则**，不写修饰：
- 不写事故叙述与事故措辞：「已真实发生」「实测」「真机」「有人踩过」「维护者拍板」、「评审 r2-b1 P2-1」、批次与轮次（r9 / b3）；
- **代码注释不写票号**（例如：「票 #109」）——溯源靠 git 历史与票面；
- 不写口号（「宁可重复，不要指望继承」）与自我辩解（「这是有意的：…」）；
- 不写口语与语气词（「别指望」「人话」）；
- 理由只留一句；不用「很重要 / 务必」这类强调词。

## 要维护者拍板时

- **假定维护者是技术小白**：用大白话，不出现内部术语 / 函数名 / 文件路径；非要出现，先一句说清它是干什么的。
- **有明确选项要维护者选时，用 `ask_user_question` 弹窗。**
- 每个选项写清**做什么、好处、坏处**（填进选项的 description，不是正文）。
- 不要求维护者先去读别处才能决定；该说明的当场说明。

## 需求边界与决策原则

- **严格按约定实现**：票面、规格及需求未明确的功能，不得自行设计或扩展。
- **不确定就确认**：存在歧义或无法判断时，不得猜测，先向维护者确认。

## 门禁

**单测即门禁**。命令按平台各一条（本机 = Linux）：

- Linux/macOS：全量（收尾跑一次）`./gradlew testDebugUnitTest`；定向（只跑改动涉及的测试类）`./gradlew testDebugUnitTest --tests '<全限定类名>'`
- Windows：全量 `gradlew.bat testDebugUnitTest`；定向 `gradlew.bat testDebugUnitTest --tests "<全限定类名>"`

### 临时产物落位

- 跑测试前 `mkdir -p tmp/tests` 并把 `TMP`、`TEMP`、`TMPDIR` 三个变量都指到它（测试 JVM 由 Gradle daemon fork，认 daemon 环境：Windows 读 `TEMP`/`TMP`，POSIX 读 `TMPDIR`；清单解析、Robolectric、conscrypt 等原生库也往 `java.io.tmpdir` 写）。`tmp/tests` 只由主代理在批次收尾统一清。

### 子代理 Android SDK

- worktree 里没有local.properties ⇒ 子代理靠环境变量 `ANDROID_HOME` 传入。

## 批量实施技能（implement-pro）

- `gate` = 上节全量命令；`verify` = 上节定向命令（跑点与判据以该技能为准）
- `rules`：注释用中文；文件一律 UTF-8 无 BOM + LF（禁 BOM、禁 CRLF）
- `worktreeBaseDir`：`/home/cc/.worktrees`
- `<state-dir>`：`.implement-pro/`
- 最终产物（APK）：终审 → 全量门禁之后由主代理串行出，一批出一次。
