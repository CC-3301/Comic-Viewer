# AGENTS.md

## 创意/UI 类需求

LOGO、图标、配色、布局、字体等视觉与体验决策：**先出方案，维护者确认后才进入下一步**。

## 门禁

单测即门禁：Windows 用 `gradlew.bat testDebugUnitTest`；Linux/macOS 用 `./gradlew testDebugUnitTest`（`./gradlew` 在 cmd 下会报「不是内部或外部命令」，14ms 假失败）。
改动不得降低用例数、不得放宽既有断言。

## 代理技能（Agent skills）

### 工单（Issue tracker）

工单放在本仓库的 GitHub Issues（用 `gh` CLI 操作）；规格在 `docs/SPEC.md`，**不往工单里贴规格**。详见 `docs/agents/issue-tracker.md`。

### 分诊标签（Triage labels）

沿用五个标准分诊标签，不自造新标签：`needs-triage`、`needs-info`、`ready-for-agent`、`ready-for-human`、`wontfix`。详见 `docs/agents/triage-labels.md`。

### 领域文档（Domain docs）

单上下文（single-context）：仓库根一个 `CONTEXT.md` + `docs/adr/`。详见 `docs/agents/domain.md`。
