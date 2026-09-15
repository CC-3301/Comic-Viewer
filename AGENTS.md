# Comic-Viewer

## 安全规则

- **发布前确认（固定流程）**：push、打 tag、GitHub Release 等一切对外发布动作，必须先列明发布内容（版本号、tag、Release notes、资产），征得维护者明确同意后才能执行，不得擅自发布。

## Agent skills

### Issue tracker

Issues live in this repo's GitHub Issues (via the `gh` CLI); long-form specs live in `docs/specs/` and are never pasted into issues. See `docs/agents/issue-tracker.md`.

### Triage labels

Uses the five canonical triage labels as-is (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`). See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: one `CONTEXT.md` + `docs/adr/` at the repo root. See `docs/agents/domain.md`.
