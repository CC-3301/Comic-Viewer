# AGENTS.md

## 创意/UI 类需求（LOGO、图标、配色、布局、字体等视觉与体验决策）先出方案，维护者确认后才进入下一步。

## 门禁

单测即门禁：Windows `gradlew.bat testDebugUnitTest`；Linux/macOS `./gradlew testDebugUnitTest`（`./gradlew` 在 cmd 下会报「不是内部或外部命令」，14ms 假失败）。
改动不得降低用例数、不得放宽既有断言。

## Agent skills

### Issue tracker

Issues live in this repo's GitHub Issues (via the `gh` CLI); the spec lives in `docs/SPEC.md` and is never pasted into issues. See `docs/agents/issue-tracker.md`.

### Triage labels

Uses the five canonical triage labels as-is (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`). See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: one `CONTEXT.md` + `docs/adr/` at the repo root. See `docs/agents/domain.md`.
