# 从 Windows 迁移到 Linux（一次性清单）

> 生成：2026-09-22 · 读者：在新 Linux 机器上接手这条线的人（或维护者本人）
> **本文件不是规格的一部分**，迁移完成后可以删掉。
> 本文档由 Windows 侧编排者撰写；已把 `HANDOFF.md`（原本停在批次 8）里仍然有效的经验提炼到 §5。

---

## 0 · 一句话

**仓库本身平台无关** —— 规格里出现的「Windows」是**领域内容**（本 App 的核心卖点之一就是排序规则对齐 Windows 资源管理器），不是环境假设，见 §6。

要改的只有「环境」那一层：**仓库内 2 处 + 仓库外 3 处**，另有 **4 项迁移后验证**。

---

## 1 · 仓库内要改（2 处）

### 1.1 `AGENTS.md`（唯一的平台相关文档）

| 位置 | 现状（Windows） | Linux 上应改成 |
|---|---|---|
| 「门禁」段 | `Windows 用 gradlew.bat testDebugUnitTest；Linux/macOS 用 ./gradlew testDebugUnitTest（`./gradlew` 在 cmd 下会报「不是内部或外部命令」，14ms 假失败）` | 只留一句：`单测即门禁：./gradlew testDebugUnitTest`（`gradlew.bat` 那半句与「cmd 假失败」的解释可以整段删掉） |
| 「跑门禁前的前置」代码块 | `TMP=… TEMP=… TMPDIR=… gradlew.bat testDebugUnitTest` | 末尾命令改 `./gradlew testDebugUnitTest`（**三个变量建议保留**：POSIX 读 `TMPDIR`，但 Gradle/JVM 与部分原生库也会看 `TMP`/`TEMP`） |
| 同段第 1 条 | 「Windows 读 `TEMP`/`TMP`、POSIX 读 `TMPDIR`」 | 可精简为「POSIX 读 `TMPDIR`（顺带设 `TMP`/`TEMP` 更保险）」 |
| 同段 `ANDROID_HOME` 那条 | `（本机 D:\Software\Android\Sdk）` | 换成 Linux 上的 SDK 路径（或写 `$ANDROID_HOME`），并确认 `sdk.dir` 不被 worktree 依赖 |

> 精简收益：门禁段约 12 行 → 7 行。

### 1.2 `.implement-pro/state.md`

它是**本地台账**（`.gitignore` 里，不入库），需要重写两处：

- 所有 `D:/Pi-Project/...` 路径（worktree 根、仓库路径）
- 台账内容本身：批次 1–9 的历史可以压成一行，重点保留「当前批次 / 常驻红线 / 待维护者」

### 1.3 （可选）`.gitattributes`

现在是 `* text=auto`（工作区行尾跟随本机设置）。**索引本来就是全 LF**，迁到 Linux 后工作区自动变 LF，**无需改动**；只是注释里那句「若将来要强制工作区也用 LF」可以删掉。

---

## 2 · 仓库外要改（3 处，不改会直接卡住）

### 2.1 `~/.pi/agent/extensions/subagent/config.json`

```json
{ "worktreeBaseDir": "D:/Pi-Project/.worktrees" }
```

- 改成 Linux 上的**仓库外**目录，例如 `"/home/<user>/.worktrees"`
- **托管分配拒绝把仓库内目录当根**，所以不能设成 `<repo>/.worktrees`
- 不改的后果：所有 `worktree: true` 的派活**直接失败**

### 2.2 全局记忆 `~/.pi/agent/AGENTS.md`

Windows 机器上那份通篇是 Windows 规则：PowerShell/pwsh 优先、C 盘写入禁令、`%TEMP%`、`gradlew.bat` 等。换机器后需要重写为 Linux 版（`bash`/`mktemp` 落在项目 `tmp/`、`chmod +x gradlew`、`./gradlew`）。**改它需要维护者当次同意。**

### 2.3 Android SDK 与 `local.properties`

- Linux 上准备好 SDK，设 `ANDROID_HOME`（或写 `local.properties`）
- 若用 `worktreeSetupHook` 给 worktree 拷 `local.properties`，记得把新路径写进去
- **改完环境变量必须重启 pi**，否则子代理继承不到

---

## 3 · 怎么搬（两种方式）

### 3.1 `git clone`（推荐）

```bash
git clone <remote> Comic-Viewer && cd Comic-Viewer
./gradlew testDebugUnitTest          # 先跑一次门禁确认环境就绪
```

- 带走的东西只有**已提交内容**：源码、`docs/SPEC.md`、`CONTEXT.md`、`AGENTS.md`
- **不会**带走：`.implement-pro/`（台账、packet、APK）· `references/`（素材）· `tmp/`
  ⇒ 若要保留台账与素材，**手工拷贝** `.implement-pro/{state.md,open-issues.md,name-windows-order.txt}` 与 `references/`；**`HANDOFF.md` 也在 `.gitignore` 里，同样要手工拷**（它是给接手 agent 的总入口）

### 3.2 整目录拷贝

**不要带上**这些（已在 Windows 侧清掉或本就无用）：`app/build/` · `.gradle/` · 旧 APK · 历史 packet · `tmp/`。

---

## 4 · 迁移后验证（4 项，逐条打勾）

- [ ] **门禁**：`mkdir -p tmp/tests && TMP=$PWD/tmp/tests TEMP=$PWD/tmp/tests TMPDIR=$PWD/tmp/tests ./gradlew testDebugUnitTest` → **137 suites / 1219 用例 / 0 失败 / 1 skipped**（批次 9 收尾时的基线）
- [ ] **worktree 隔离自检**：派一个 `worktree: true` 的子代理，30 秒内确认「`git worktree list` 多出 `pi-worktree-*`」且「主检出 `git status` 仍为空」——**这条不过就不要继续派活**（见 §5.1）
- [ ] **工单可见**：`gh auth status` 正常、`gh issue list --state open` 能列出 `#60/#111/#113/#114/#115/#116/#117/#118/#119`
- [ ] **脱敏词表在位**：`.implement-pro/name-windows-order.txt`（145 条候选）存在，`push` 前扫一遍

---

## 5 · 环境无关的长期经验（**别丢** —— 提炼自原 `HANDOFF.md`，原地已删）

### 5.1 worktree 隔离事故（真实发生过两次，务必保留这条）

**症状**：派活时漏了正确形状 ⇒ 多个 worker **共用主检出**，直接往主分支写、把工作树改脏。

**正确形状**：**一票一次顶层调用** + `implement.js` + `worktree: true`。

**30 秒自检（派完立刻做）**：
1. `git worktree list` 必须多出新的 `pi-worktree-*`
2. 主检出 `git status --porcelain` 仍为空

不满足就**立刻停掉子代理**再排查。止损时先把脏 diff 存档（`tmp/incident/`）、再用 `git checkout -- <具体文件>` 还原；**确认 HEAD 没有被推进**。

> 反例：把 `worktree: true` 挂在「一个顶层 raw `workflowScript`」上**不生效** —— 已实测（18 分钟内验证并止损）。

### 5.2 门禁口径

- 三个变量都要设（`TMP`/`TEMP`/`TMPDIR` 指向项目内 `tmp/tests`）：测试 JVM 由 Gradle daemon fork，认的是 **daemon 的环境**；漏设会在系统盘堆几百个临时目录 + 原生库（一次约 250MB）
- 跑完**核对产物确实落在 `tmp/tests`**
- **不许 `gradlew --stop`**：会停掉本机所有同版本 daemon，可能中断同机其它子代理正在跑的测试（已真实发生）
- 用例数不降、既有断言不放宽

### 5.3 评审与裁决

- 评审对象是**冻结 sha**；代码一改，上次 approved 作废
- 双轴评审：**spec 轴**（票面验收 + SPEC 是否落实）与 **standards 轴**（仓库规范 + 气味），两轴互不引用
- **P0/P1 必修；P2 只记录**
- 实现缺陷类修复轮 **≤2 轮**（超了把报告摊给维护者，不自己循环）；**口径变更类不计入**（例如项目把「注释与实现不符」按 P1 处理时）
- 集成：`merge --no-ff` 保冻结 sha，并做**逐字节一致性核对**（`git diff <票定版 sha> HEAD -- <本票文件集>` 必须 0 行）

### 5.4 脱敏（公开仓库）

- 绝不在任何文字里复述真实作品名/社团名/作者名（含「我把 X 换成 Y」这种说明）
- 测试与文档样本一律**合成名**
- **push 前先扫再推**：在待推提交上跑真名 grep（词表 `.implement-pro/name-windows-order.txt`），0 命中才推；**grep 与 push 分两条命令**

### 5.5 删除安全

- 只删**具体路径**（不许通配符、不许递归整目录）
- 先列**清单**（每个路径 + 单项大小 + 理由），用户当次明确确认后才动手
- 即使事先笼统同意过，**执行前仍要二次确认**

### 5.6 文档纪律

- `AGENTS.md` 改动需**当次**明确同意（事先的概括授权不算数）
- `docs/SPEC.md` / `CONTEXT.md` / `.implement-pro/state.md` 目前**不在**冻结清单里（维护者 2026-09-22 收窄了清单），但改动仍建议给前后对比

---

## 6 · 文档体检结论（本次清点，供参考）

| 文档 | 结论 |
|---|---|
| `docs/SPEC.md`（276 行） | **平台无关**。出现的每一处「Windows」都是**领域内容**（排序对齐 Windows 资源管理器、`WindowsNameOrderLocalProbeTest` 本地探针）⇒ **不要改** |
| `CONTEXT.md`（175 行） | 同上（`CONTEXT.md:166` 那条「与 Windows 资源管理器一致」是领域规则） |
| 两者是否精简 | **不建议**：它们是规格，砍句子等于丢约束。若真要动，只做「子节合并」不动内容 |
| 与实现是否一致 | 批次 9 的落地值（滑条 7dp、过渡 240ms、预览条保底 201dp）**均已同步**；`#60`（滑条 → 10dp + 网格档跳格修复）与 `#111`（横向整屏滑入划出 + 帧时长量化）的新口径**尚未实现**，落地后再同步，票面已写明具体要改哪几行 |

---

## 7 · 已在 Windows 侧清掉的东西（迁移不用带）

| 项 | 大小 | 说明 |
|---|---|---|
| `app/build/` | 138 MB | 构建产物 |
| `.gradle/`（项目内） | 31 MB | Gradle 缓存，含 Windows 路径 |
| `ComicViewer-batch7-final-debug.apk` · `ComicViewer-batch8-final-debug.apk` | 31.7 MB | 旧交付包 |
| `.implement-pro/*.log`（25 个） | ~100 KB | 一次性构建/门禁日志 |
| `.implement-pro/` 里已关票的 packet（48 个票目录） | ~7.5 MB | 已关票的评审与证据 |
| `tmp/incident/` · `tmp/preview-*.html` | 461 KB | 编排事故存档 + 两个方案演示页 |
| `HANDOFF.md` | 10 KB | 停在批次 8，经验已提炼到本文 §5 |

**保留**：`ComicViewer-batch9-final-debug.apk`（批次 9 仍在真机验收期）· `state.md` · `open-issues.md` · `name-windows-order.txt` · 开放票 packet（`60/111/113/118` + `_batch9`）· `references/`

---

## 8 · 迁移完成后的收尾

1. 更新 `.implement-pro/state.md` 的「当前状态」段（仓库路径、worktree 根、门禁确认结果）
2. 按 §4 打勾四项验证
3. 本文件可留作参考，也可删（迁移是一次性的）
