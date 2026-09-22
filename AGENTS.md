# AGENTS.md

## 创意/UI 类需求

LOGO、图标、配色、布局、字体等视觉与体验决策：**先出方案，维护者确认后才进入下一步**。

## 文档改动需确认

改动 `AGENTS.md`（本文件）、`docs/SPEC.md`、`CONTEXT.md`、`.implement-pro/state.md` 之前，先向维护者说明**要改什么、为什么**，**得到当次明确同意后才动手**——**事先的概括授权不算数，每次都要重新确认**；改完给出前后对比。

## 门禁

单测即门禁：Windows 用 `gradlew.bat testDebugUnitTest`；Linux/macOS 用 `./gradlew testDebugUnitTest`（`./gradlew` 在 cmd 下会报「不是内部或外部命令」，14ms 假失败）。
改动不得降低用例数、不得放宽既有断言。

**跑门禁前的前置**（`implement-pro` 技能侧通过 `verifyPrelude` 传给子代理）：

```bash
mkdir -p tmp/tests
TMP=<repo>/tmp/tests TEMP=<repo>/tmp/tests TMPDIR=<repo>/tmp/tests gradlew.bat testDebugUnitTest
```

- **三个变量都要设**，只设一个不生效：测试 JVM 由 Gradle daemon fork，认的是 **daemon 的环境**；Windows 读 `TEMP`/`TMP`、POSIX 读 `TMPDIR`。漏设会在系统盘堆出几百个临时目录 + 原生库（一次约 250MB）。
- 跑完**核对新产物确实落在 `<repo>/tmp/tests`**；落在系统盘就写进证据，由编排者在串行门禁阶段处理。
- **不许 `gradlew --stop`**：会停掉本机所有同版本 daemon —— 可能中断**同机其它子代理正在跑的测试**（已真实发生）。需要换环境时由编排者串行做。
- worktree 里**没有 `local.properties`** ⇒ 子代理需要 `ANDROID_HOME`（本机 `D:\Software\Android\Sdk`），或由 `worktreeSetupHook` 拷入；改环境变量后**必须重启 pi**。
- 清单解析 / Robolectric / conscrypt 等原生库也往 `java.io.tmpdir` 写，同样受上面三个变量约束。

## 脱敏

- **绝不在公开仓库的任何文字里复述真实作品名/社团名/作者名**，含「我已把 X 换成 Y」这类说明——一律写「此处不复述具体名字」。
- 测试与文档样本一律用**合成名**，不得从 `references/` 素材抄名字。
- **push 前先扫再推**：在待推提交上跑真名 grep（本机词表 `.implement-pro/name-windows-order.txt`，145 条候选），0 命中才推；grep 与 push 分两条命令，别写在同一行。

## 代理技能（Agent skills）

### 工单（Issue tracker）

工单放在本仓库的 GitHub Issues（用 `gh` CLI 操作）；规格在 `docs/SPEC.md`，**不往工单里贴规格**。详见 `docs/agents/issue-tracker.md`。

### 分诊标签（Triage labels）

沿用五个标准分诊标签，不自造新标签：`needs-triage`、`needs-info`、`ready-for-agent`、`ready-for-human`、`wontfix`。详见 `docs/agents/triage-labels.md`。

### 领域文档（Domain docs）

单上下文（single-context）：仓库根一个 `CONTEXT.md` + `docs/adr/`。详见 `docs/agents/domain.md`。

## 批量实施技能（implement-pro）的占位符取值

| 占位符 | 本仓取值 |
|---|---|
| `gate` / `verify` | 见「门禁」；`verifyPrelude` = 上面那段前置 |
| `<state-dir>` | `.implement-pro/` |
| packet 结构 | `.implement-pro/<票>/{brief.md, diff-rN.patch, evidence-impl.md, review-spec-rN.md, review-standards-rN.md}` |
| 批次分支 | `feat/<batch-slug>`；PR **故意不合入**（合了 GitHub 会自动关掉待验收的票） |
| 人工验收 | 票面含真机项 ⇒ 状态 `awaiting-human`、留言写明、**不关票**；验收通过后按维护者指示关票 |
| 越界发现 | 不当场做；批次末尾汇总成票（与在飞票**同文件**时并入或排后，否则必冲突） |
| 删除安全 | 见全局记忆「删除操作安全规则」：只列**具体路径** + 单项大小 + 理由、**当次二次确认**、不许通配符/递归整目录 |
