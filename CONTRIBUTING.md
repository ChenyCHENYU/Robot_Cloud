# 贡献指南

Robot Cloud 的业务构建使用 Maven；Bun 与 Node 仅管理提交校验和 BD 开发工具。服务运行不依赖它们。

## 开发准备

使用 JDK 17、项目支持的 Maven，以及 Node >=22.18.0、Bun 1.4.2。在仓库根执行：

```bash
bun install --frozen-lockfile
git config --get core.hooksPath # 应为 .husky/_
```

安装过程中 `prepare` 启用仓库本地 Husky。不要运行 BD 的 `git config core.hooksPath .githooks`，它会切换到另一套提交策略。CI 用 `HUSKY=0` 安装工具，然后直接执行权威检查，不依赖本地 Hook。

## 提交标题

```text
type(scope): message
```

- `type`：`wip`、`feat`、`fix`、`docs`、`style`、`refactor`、`perf`、`test`、`chore`、`revert`、`build`、`deps`。与 Robot Admin 的实际 commitlint 配置一致。
- `scope`：必须非空，允许自定义，如 `auth`、`system`、`auth/session`、`tooling`、`db`、`readme`。不要求 BD Catalog 模块登记。
- `message`：说明具体行为，支持中英文；遵循 conventional 其余规则，例如标题不超过 100 字符、结尾不加句号。无须写“功能点-具体内容”。

```bash
git commit -m "feat(system): add company membership commands"
git commit -m "fix(auth/session): 拒绝已撤销的会话"
git commit -m "chore(ci): enforce scoped commit messages"
```

`feat: ...`、`fix(): ...`、`ci(tooling): ...` 会被拒绝。类型清单不含 `ci`；使用 `chore(ci)` 或 `build(ci)`。Git 默认普通 merge 标题允许保留，版本标题如 `v1.0.0` 不自动豁免。

本地 Hook 与 CI 共用 `commitlint.config.cjs`。`--no-verify` 可绕过本地 Hook，CI 仍检查整个历史范围，包括标题错误但 HEAD 合规的中间提交。已公开的旧历史以 `.commit-policy.json` 的 `historicalHead` 为边界保留，不强推、不自动更新边界。

## 检查入口

```bash
bun run test:tooling       # 真 Git Hook、提交范围及 BD 新问题阻断测试
bun run check:commits      # 边界之后所有可达提交
bun run check:bd           # 原始全规则报告 + Robot 31 条通用规则门禁
bun run bd check --target . --json # 安装清单和受管资产漂移
scripts/verify.sh          # Java/Python、机器契约、迁移和打包 Mapper
```

涉及数据库变更时，按 [部署说明](docs/deployment.md) 配置隔离 loopback MySQL，再执行 `scripts/verify.sh --mysql-upgrade`。提交前查看暂存内容并运行 `git diff --cached --check`；测试结果、环境恢复和前端联调分别记录。

BD 的原始 B1 扫描尚不识别 Sa-Token；`check:bd` 明示适用范围，并保留原始失败结果。已有发现基线不等于修复完成，不得自动重新生成基线让新问题通过。安装和规范适配的准确边界见 [BD 映射](docs/conventions-bd-mapping.md)。

## AI 与 BD

先读项目 [AGENTS.md](AGENTS.md) 和 [BD 原生入口](.agents/skills/wl-skills-bd/SKILL.md)，再按任务读取规范。使用固定依赖中的 CLI，不依赖全局安装或兄弟项目路径：

```bash
bun run bd task --input "<具体后端任务>" --target . --json
# 从 notice 读取 runId；原始工具结果和实际验证按同一 runId 留存
bun run bd validate --target . --run-id <runId> --json
WL_TASK_RUN_ID=<runId> bun run check:bd
bun run bd status --target . --run-id <runId> --json
bun run bd doctor-host --host codex --target . --json
```

默认 Robot profile 和安装的 JH4J profiles 都禁止代码生成；不得把 Java 8、`javax.validation`、平台坐标、成功码 2000 或 Java 8 质量 profile 移植到 Robot。新增生成能力需先完成 Java 17/Boot 3 适配及真实验证。

升级 BD 时，先在 `package.json` 固定新版本、更新 `bun.lock`，检查 release 与 profile 差异，然后走 `bd update` 的预览、planHash、确认和复验。更新后重跑工具行为测试、`bd check`、宿主版本诊断和实际业务门禁。受管文档不手改；Robot 的项目配置、禁止生成 profile 和映射文档由项目维护。

## 分支、PR 与发布

从最新 `dev` 创建功能分支，经 PR 合回 `dev`，再将验证后的集成结果发布到 `main`。GitHub 是主协作仓库，Gitee 同步相同提交；两边都禁止强推共享历史。完整流程见 [Git 维护](docs/git-workflow.md)。

PR 说明具体问题、修改后行为、实际检查、接口/迁移兼容性和未验证项。托管 workflow 的存在不代表执行成功，仓库保护也需实际站点设置；平台拒绝启动或检查未执行时不得登记为通过。
