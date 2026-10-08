# 项目记忆

## 代理技能（Agent skills）

### 工单（Issue tracker）

工单放在本仓库的 GitHub Issues（用 `gh` CLI 操作）；**规格入口** = `docs/SPEC.md`（索引 + 全局口径）+ `docs/spec/*.md`（分领域分册：`sources` / `browsing` / `reader` / `startup` / `shell`），**不往工单里贴规格**。详见 `docs/agents/issue-tracker.md`。

未经同意不要擅自开新票。

### 分诊标签（Triage labels）

沿用五个标准分诊标签，不自造新标签：`needs-triage`、`needs-info`、`ready-for-agent`、`ready-for-human`、`wontfix`。详见 `docs/agents/triage-labels.md`。

### 领域文档（Domain docs）

单上下文（single-context）：仓库根一个 `GLOSSARY.md` + `docs/adr/`。详见 `docs/agents/domain.md`。

## 调查与探索

- 调研、探索及 implement skill 的实现前分析阶段，派 Explore subagent；由它直接调用 codegraph MCP，不嵌套subagent，不靠逐个读文件推断。
- 涉及第三方库或框架时，使用 context7 MCP 查询官方文档；先取得库 ID，一次查询一个概念，不凭记忆作答。

## 文档维护

- 当设计、行为与规范文档SPEC.md、领域文档GLOSSARY.md/ADR对不上时，及时向维护者询问是否要更新文档。

## 需求边界

- **严格按约定实现**：票面、规格及需求未明确的功能，不得自行设计或扩展。

## 文档与注释的写法

- **代码注释不写票号**。
- **只记录事实与规则**，保持简洁。
- 不写事故叙述、过程信息、口号或自我辩解。
- 理由只留一句，不使用强调性措辞。

## 脱敏

票面、评论、提交信息、代码注释、规格里**一律不写真机现场标识**——包括：
IP（含端口）、主机名、共享名、库名、**目录名**、**书名**、连接显示名。

- 用占位：`<host>`（带端口写 `<host>:<port>`）、`<share>`、`<库名>`、`<目录名>`、`<书名>`。
- 日志不要原样粘贴：先逐行把路径/各层名字换成占位再贴。

## 最终产物（APK）

- 一批出一次，只在最终收尾时出，出release包而不是debug包。
