# Git 维护流程

本项目以 `main` 为稳定主分支，以 `dev` 为日常集成基线。功能分支从最新 `dev` 创建；每次任务完成并通过适用验证后，提交推送，再依次以 `--ff-only` 快进到 `dev`、`main`，同步 GitHub 和 Gitee 并核对 SHA。交付不只停在功能分支，两个主干保持线性历史。

## 仓库与远程

| 远程 | 地址 | 职责 |
| --- | --- | --- |
| `origin` | `git@github.com:ChenyCHENYU/Robot_Cloud.git` | 主要协作仓库，提交 PR、运行 GitHub Actions |
| `gitee` | `git@gitee.com:ycyplus163/robot_-cloud.git` | 同步 `main`、`dev`，保持相同提交历史 |

首次 clone 后配置第二远程及本地协作习惯：

```bash
git clone git@github.com:ChenyCHENYU/Robot_Cloud.git
cd Robot_Cloud
git remote add gitee git@gitee.com:ycyplus163/robot_-cloud.git
git config remote.pushDefault origin
git config push.default simple
git config pull.ff only
git config merge.ff only
git switch --track origin/dev
bun install --frozen-lockfile
```

上述配置仅作用于本地仓库。两个站点的默认分支均应设为 `main`；本地分支命名、远程跟踪和站点默认分支是不同设置。远程 SSH 权限需分别配置，只向站点登记公钥，私钥、访问令牌及真实环境配置不入库。

## 日常开发

工作区先保持可切换状态，再更新基线：

```bash
git switch dev
git fetch origin
git pull --ff-only origin dev
git switch -c feature/company-profile
```

命名使用 `feat/<主题>`（兼容既有 `feature/`）、`fix/<主题>`、`refactor/<主题>`、`chore/<主题>` 或 `docs/<主题>`，主题采用简短英文，关联工单时可带编号。一个分支聚焦一项可独立审查的变化。

提交前查看 `git diff` 和 `git status`，按实际范围暂存文件，运行 `scripts/verify.sh`。涉及数据库升级时，在独立 loopback MySQL 上执行 `scripts/verify.sh --mysql-upgrade`，按 [部署说明](deployment.md) 配置验证变量。真实环境恢复和前端联调另行记录，不用单元测试代替。

```bash
git diff --check
git commit -m "feat(system): add company profile commands"
git push -u origin feature/company-profile
```

PR 可用于审查记录，填写问题、结果、实际验证、API/数据库兼容性及未验证项；站点保护要求 PR 时遵守实际保护。合入使用快进，不新增 merge commit。功能分支出现分叉时，先核对双方提交，在未共享分支中整理待交付提交并重新验证；保留已共享历史，不强推或自动用 merge commit 绕过 `--ff-only`。

## 集成与发布

1. 合入前完成适用检查与审查；托管 CI 未运行或失败必须如实说明，不能登记为通过。数据库变化同步 Flyway、机器合同、升级预检和恢复说明；前端字段变化同步 Robot Admin。
2. 提交标题使用 `type(scope): message`，推送功能分支后，先将功能分支快进到 `dev`，再将 `dev` 快进到 `main`；使用明确的 `--ff-only`，不新增 merge commit，不对已共享历史 rebase 或 squash。
3. 同步两个远程的 `main/dev`，核对四个远端引用均指向本次交付提交。保留清洁工作区，交付后回到 `dev`，下个功能分支继续从最新 `dev` 创建。
4. 正式版本增加标签及生产部署仍按发布规则验收；主干同步不代表生产环境已验收，不把 `1.0.0-SNAPSHOT` 自动当作正式发布。紧急修复也须同步到两个主干，避免遗失。

已验证功能分支的集成示例：

```bash
set -euo pipefail
git fetch origin
git fetch gitee
git switch dev
git merge --ff-only origin/dev
git merge --ff-only gitee/dev
git merge --ff-only feat/company-profile
git switch main
git merge --ff-only origin/main
git merge --ff-only gitee/main
git merge --ff-only dev
git push --atomic origin dev main
git push --atomic gitee dev main
git ls-remote --heads origin dev main
git ls-remote --heads gitee dev main
git switch dev
```

所有合并均须成功快进后才能继续；任一远端出现新提交时先核对并整理，再验证，不用 force 覆盖。单站 `--atomic` 保证该站两个引用一起更新；不支持 atomic 的站点需明确说明并逐个推送、核对结果，两个站点之间仍无原子事务。

## 双远程同步

GitHub 是主要协作入口。同步前读取两个站点的最新状态，只向可快进的分支推送；发现 Gitee 独立提交时先审查并整理线性提交，再同步，不覆盖远端历史。

```bash
git fetch origin
git fetch gitee
git push --atomic origin main dev
git push --atomic gitee main dev
git ls-remote --heads origin main dev
git ls-remote --heads gitee main dev
```

两个远程返回的同名分支 SHA 应一致。两个站点之间没有跨站原子事务：一个站点失败时保留已成功提交，修复认证或网络后补推，并核对 SHA；不要通过 force 或回退另一个站点来掩盖失败。功能分支默认只推 GitHub；按实际需要同步 Gitee。

## 站点保护设置

在 GitHub 和 Gitee 的仓库设置中将默认分支设为 `main`。建议保护 `main`、`dev`：要求审查、线性历史，禁止 force 和删除主干；采用满足快进要求的审查与集成方式。GitHub 的合入检查选择真实运行生成的 `verify` job；Gitee 的检查依据其实际可用能力配置，不能把 GitHub 工作流文件的存在等同于 Gitee 已运行 CI。

这些设置需要站点管理权限，文档不代表它们已经启用。首次建立分支后核对远端 HEAD、分支 SHA 和 CI 执行记录，再按站点提供的能力完成保护。

## 提交与仓库卫生

提交标题必须采用 `type(scope): message`，scope 必填，类型白名单与 Robot Admin 相同：`wip`、`feat`、`fix`、`docs`、`style`、`refactor`、`perf`、`test`、`chore`、`revert`、`build`、`deps`。`ci` 不在这份白名单中，CI 变化使用 `chore(ci)` 或 `build(ci)`。scope 允许自定义，建议 `auth`、`gateway`、`system`、`security`、`common`、`api`、`bom`、`db`、`tooling`、`readme`；描述结果，避免写“update”。

`bun install --frozen-lockfile` 安装固定版本的开发工具并启用 Husky 的 `commit-msg`；普通 `git commit` 自动运行 commitlint。完整格式、真实 Hook 测试和 CI 范围规则见 [贡献指南](../CONTRIBUTING.md)。本地 Hook 能被绕过，因此 CI 还会验证整个提交范围。`.commit-policy.json` 明确记录已共享历史的截止 SHA，之后所有可达提交都必须合规，不只检查 HEAD。已有历史不强推改名，不自动移动截止 SHA。

BD 安装器附带的 `.githooks` 和 `.github/git-hooks` 保持未启用；`core.hooksPath` 使用 `.husky/_`。它们不是 Robot 的提交事实源，不叠加模块白名单或“功能点-具体内容”的额外要求。Git 自动产生的普通 merge 标题允许保留；普通功能标题和版本标签式标题仍须满足 commitlint。

只提交源码、测试、机器契约、迁移、构建工具及文档。构建目录、运行日志、本地敏感配置、工具缓存和本机执行回执通过 `.gitignore` 排除；已有受跟踪文件需要取消跟踪，忽略规则才会生效。本次工作区原始证据保存在仓库外的 `output/`，仓库内的 [验收摘要](verification-2026-10-09.md) 和托管 CI artifact 分别保留可审查结果。
