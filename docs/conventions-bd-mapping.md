# wl-skills-bd 与 Robot 规则映射

当前版本：`@agile-team/wl-skills-bd@0.37.0`；本轮runId：`41f534f8-623d-4673-80ea-462e2f5e1907`。目标为Java17后端，不适用PC Kit或JH4J固定目录、Java8、成功码及数据库约定。原生包资产、原始报告和项目适配规则分别保留，不把适配门通过写成供应商32条或J门全部通过。

## 当前执行入口

| 入口 | 实际作用与限制 |
| --- | --- |
| `bun run check:bd` | 原始B1–B32及B2–B32审查保留退出码和finding；按既有精确fingerprint基线及独立源码语义复核计算项目有效门，新增error/warning阻断，禁止自动刷新基线 |
| 精确语义复核 | 三个已排除Lombok输出的确切字段与23文件认证测试同包建议，按路径、类型、注解及固定源码哈希复核；保留原始失败，未知项与哈希漂移阻断，不停用B25/B6或放宽零警告预算 |
| `scripts/tooling/robot_policy.mjs` | 8项RBD检查补Robot实际Sa-Token/公共和本人端点/内部调用、公司隔离与软删、分页、DTO输出及机器合同；覆盖静态路径，运行授权仍由行为测试验真 |
| 输入快照 | 递归记录真实业务源码、合同、配置及规则；只排除物理生成目录、工具收据和私有环境文件。不得用文件名屏蔽业务源码 |
| `bun run test:tooling` | 提交父数、范围内中间提交、真实hook、生成profile阻断、规则适配和缓存TTL负例 |
| `scripts/verify.sh --mysql-upgrade` | 真实Java17/Maven固定工具链；行为、架构、HTTP/全结构数据库合同；逐类Checkstyle/PMD7/SpotBugs/Spotless/JaCoCo；完整SBOM/漏洞/许可；真实MySQL升级竞争及最终Mapper/许可打包检查 |
| 原生 `bd status` | 同runId收尾；保留原始失败、pending/gap和宿主自动加载未验证；不能通过模型自报把J门改成通过 |

当前正式Java质量由父POM的`robot-quality`执行，默认验证脚本强制启用，不沿用供应商Java8示例或只登记pluginManagement。每个服务实现类（含业务helper/内部类）要求行覆盖率≥70%、分支≥60%；每个Controller行覆盖率≥50%。原生报告必须属于同次成功Maven、无缺失/陈旧/处理错误；不使用包均值或整包排除。

供应链按当前CycloneDX真实解析版本执行OSV完整批量及分页查询；网络错误、身份不一致、未知许可、漏洞及过期评审失败关闭。MySQL Connector的GPL/Universal FOSS条款仅按该确切版本、根MIT许可、公开对应源码及保留原始通知条件评审，不声明闭源使用自动获准。

## 当前项目规则

| 要求 | 当前落地 |
| --- | --- |
| 架构 | Controller→Service→Mapper；common/API不依赖服务实现。User查询/成员角色命令、菜单拓扑/写值/投影、CacheOutboxStore持久化职责分别收敛，保持聚合事务 |
| 身份 | Sa-Token与必需Servlet主体核验；公司/context来自可信会话；内部敏感认证供给限已验证服务身份，新增受众需单独准入 |
| 授权 | 独立永久平台管理员维护全局目录；公司超管不替代平台事实；授予受永久上界约束，临授为按用户授已有角色 |
| 数据 | 公司成员主部门为事实源；账号和成员状态/版本独立。关系软删1有效/0删除；非法/停用范围拒绝 |
| 事务 | 客户端版本CAS，引用写与删除在公司协调锁内重验；业务、成功审计、epoch、Outbox同事务；失败无成功副作用 |
| 缓存 | 数据库epoch权威；通知在事务外，delivery revision围栏；quarantine、受控重放、完成历史逻辑归档及分页，不删除pending |
| 迁移 | 已发布V1–V10不可改；追加V11–V13；固定golden覆盖20表全部列和索引，新建与V10升级同时验证；V13只补导航，不增加接口权限 |
| 时间/预算 | JSON无偏移时间表示UTC并严格校验日历；显式UTC Clock/审计，JDBC4.2不经默认时区转换；数据库相对退避和有限网络/锁预算；存量临授解释由生产UTC marker确认 |
| 密码与日志 | 新密码6–32字符单元且合法UTF-8≤72字节；登录独立检查字节；DTO不输出密码/摘要，日志只记录受控类别及匿名关联 |
| 观测 | HTTP200业务失败进入低基数结果计数；不将账号/事件/key/密码设为标签；告警接收与目标阈值需要环境验收 |

正式notice、复核及status最终状态以本轮真实收据为准。托管CI因账户账单锁定未启动；用户授权完成本地验证后提交推送，不等待账户恢复。分支保护及宿主自动发现未经读取/执行确认，不宣称已生效。

## 2026-10-15 本轮实际回执

统一Java17门通过115套/679测试、20项真实MySQL与83个业务类逐类质量；运行工具UTC修补后Python53+19项通过，Node63项通过。当前不可变制品及真实运行见[本轮验收](verification-2026-10-15.md)。

同runId全规则原始报告81 error/1 warning、coverage complete，原始review仍exit1：77项B1、3项B25、1项既有B17与1项B6建议。8项Robot静态适配覆盖79端点通过；精确语义复核4项后有效门0新增/0阻断、1既有B17，原baseline及warningBudget0不变。两份原始失败保留，原生status不能被项目有效门覆盖，包J门与宿主自动加载保留各自未验证状态。

## 使用与更新

```bash
bun install --frozen-lockfile
bun run bd task --input '<具体任务>' --target . --json
WL_TASK_RUN_ID=<notice.runId> bun run check:bd
bun run bd status --target . --run-id <notice.runId> --json
bun run bd check --target . --json
bun run bd doctor-host --host codex --target . --json
```

同一任务复用runId。只在依赖、锁文件和安装清单一致时更新；不自动重建问题基线，不在多项目工作区根安装/更新。完整交付状态见[改进台账](improvement-roadmap.md)，原始发现见[全面审查](review-2026-10-14.md)。

## 历史接入记录（以下为2026-10-11及更早的执行快照）

以下原文中的版本、测试计数、“当前”与待办均指当次执行，不能覆盖上方2026-10-15实现或证明本轮验收。

## 正式接入（2026-10-11）

本项目已直接安装 `@agile-team/wl-skills-bd@0.37.0`，由 `package.json` 和 `bun.lock` 固定运行器及依赖，安装清单 `.wl-skills-bd-manifest.json` 记录 148 项资产。本次接入仅在 Robot Cloud 根目录执行，未安装前端 Kit，也未修改聚合工作区或兄弟项目。

### 工具与项目规则

| 项 | Robot 的落地方式 |
| --- | --- |
| 原生入口 | `AGENTS.md`、`.agents/skills/wl-skills-bd/SKILL.md`、13 个 canonical Skills、规范路由与机器能力清单 |
| 宿主配置 | 包提供固定 `0.37.0` 的 MCP 配置；静态入口存在不证明宿主自动发现/执行，本次通过本地 CLI 执行 |
| 提交规范 | commitlint `20.4.3` + Husky `9.1.7`，类型与 Robot Admin 一致，scope 必填且可自定义；BD 自带 Hook 不启用，不叠加 scope 模块白名单或标题连字符 |
| 非平台规则 | `bun run check:bd` 完整执行 B2–B32，显式核对 31 条规则均执行；禁止 quick/staged 结果冒充交付门 |
| 原始扫描 | 同时执行 B1–B32 并归档其原始退出码、全部问题和覆盖范围，不把原始失败报告改成成功 |
| B1 适配 | 当前扫描器仅识别平台 `@PreAuthorize`，不进入 Robot 自动阻断集合；权限仍由现有 Sa-Token、内部信任、MVC 和机器契约测试验证，完整扫描器适配待补 |
| 租户与软删 | `rules.local.json` 引用真实 `TenantLineInnerInterceptor` 装配源码，1 有效/0 删除；配置只能证明源码装配存在，运行约束仍依赖行为验证 |
| 新增问题 | `quality-gate.json` + 原生完整审查建立的 `review-baseline.json`，新增 error / warning 阻断；不自动刷新基线 |
| 生成与平台模板 | 默认 `robot-boot3-audit`、`jh4j3-openapi3`、`jh4j3-springfox-legacy` 均禁止生成；真实工具行为测试验证它们全部拒绝代码生成 |
| Java 门 | 沿用 `scripts/verify.sh` 的 Java 17、行为、ArchUnit、机器契约和打包 Mapper；未套用包中的 Java 8 `wl-quality` 示例 |
| 数据库合同 | Robot 的 `contracts/database`、Flyway 哈希清单和真实升级验收为当前事实源；未迁移到 BD 的 `wl-contract.json`/`docs/db-spec`，不能宣称 B31 已覆盖这些 Robot 合同 |

安装器 0.37.0 暂无按平台筛选受管文件的 CLI 选项，因此原始规范和模板留作可核对的包资产；项目规则排除其平台特有约束，且相关生成 profiles 机器阻断。保留资产不代表启用 JH4J。Robot 的配置、交付 profile 及禁止生成的 profiles 登记为项目所有，后续 update 保留；其他受管文档通过包更新，不手工删改或伪造安装清单。

项目维护的交付 profile 使用成功码 0、`page/pageSize`、`data.list`、上限 200；实际端点以 `contracts/system` 和 [API 合同](api-contract.md) 为准。它是审计参考，不能用来执行未经适配的 CRUD 生成。

### 扫描结果与边界

本次任务 runId 为 `546ea47e-a66d-447f-b68e-3cdd7f3fdbf8`，实际 notice 为 `matched / convention-audit-be`；校验、审查和最终 status 使用同一 runId。

| 检查 | 实际结果 |
| --- | --- |
| 受管安装检查 | `bd check` 无漂移；运行器、入口及安装版本一致为 0.37.0 |
| 全规则原始扫描 | 74 errors：B1 70、B17 4；原始扫描退出码 1，保留于 `target/verification/bd-native.json` |
| Robot 通用审查 | B2–B32 均执行；4 个既有 B17 fingerprint、0 新增问题、0 阻断，见 `target/verification/bd-robot-review.json` |
| 提交与工具行为 | 16 个用例通过，含真实 Git Hook、绕过 Hook 后的中间提交拒绝、生成阻断、真实 B13 缓存 TTL 负例 |
| 既有后端入口 | 本次 `scripts/verify.sh` 实际成功：14 模块、55 套 Java 报告/305 测试、23 个 Python 测试，失败/错误/跳过均为 0；最终 JAR 的 17 个 Mapper 装配通过。本机使用 JDK 21 编译 Java 17 目标，未重新执行 MySQL 升级、双实例或 Admin 联调 |
| CI 配置 | `Verify Robot Cloud / verify` 已接固定版本安装、范围提交校验、工具行为测试、资产漂移检查、通用规则门禁及报告归档；托管执行需看真实 Actions 结果 |
| 宿主加载 | `doctor-host --host codex` 只诊断静态入口及版本，自动发现、模型选择和自动加载仍 `unverified` |
| 原生任务 status | 同 runId 如实保留 `executionStatus=failed / validationStatus=failed`（全规则原始扫描未清零），`stale=false / planStale=false / outdatedChecker=false`；J1/J2/J3/J4/J5/J8 仍为包回执中的 pending，不把项目既有测试自报为包门通过 |

74 项完整基线保留原生审查证据。31 条通用门实际消费其中 4 项 B17：`DictService.removeById`、`MenuService.removeById/deleteById` 使用 MyBatis-Plus 逻辑删除；`CacheOutboxGovernanceIntegrationTest` 在隔离测试库故意删除审计表以验证事务回滚。这里只按既有 fingerprint 分类，不给整文件或整条 B17 豁免；新增同类问题仍会阻断。

“31 条已执行”不等于 31 个风险领域全部闭环。扫描器对 Sa-Token、Robot `PageQuery`、定制合同和部分 Lombok 敏感字段存在识别边界；已知 `toString` 泄露风险即使未报 B25，也必须继续按 [改进计划](improvement-roadmap.md) 修复。J2/J3/J4/J5 的 Java 17 工具适配和 J8 覆盖率阈值仍待落地；既有 ArchUnit/行为测试不替代这些未接入门禁。

### 后续使用与升级

```bash
bun install --frozen-lockfile
bun run bd task --input "<具体后端任务>" --target . --json
WL_TASK_RUN_ID=<notice.runId> bun run check:bd
bun run bd status --target . --run-id <notice.runId> --json
bun run bd check --target . --json
bun run bd doctor-host --host codex --target . --json
```

只在依赖、锁文件和安装版本对齐后更新。升级先预览 `bd update --target . --json`，审阅 planHash 与冲突，再携带同一哈希 `--confirm` 写入；复跑工具行为测试和业务门禁。不要自动重建问题基线或移动历史提交截止 SHA。接口、权限、数据库结构、环境和代码生成适配均需各自真实验收。

## 当前落点

| 通用要求 | 实现与边界 |
| --- | --- |
| Controller → Service → Mapper | Controller 调专用 Service；框架架构测试禁止 Controller 依赖 Mapper、RedisTemplate、JdbcTemplate。缓存 epoch/outbox 是 system 控制设施，集中在 PermissionStateStore |
| 公共层无业务实现反向依赖 | common 和 API 不依赖 auth/system 实现；security→redis、mybatis→security、api-system→security 是当前明确单向依赖 |
| 公司身份不可来自客户端 | AuthUtil 读 token Session；内部委托经过服务身份验证并与参数对齐。普通租户行插件无身份查空；登录前作用域仅限可信服务 |
| 软删与治理字段 | delete_flag：1有效、0删除；BaseEntity 管全局治理字段，TenantEntity 增加 companyId；控制表单独登记，不冒充业务实体 |
| 审计更新事实 | 每次覆盖更新人/时间，保留创建字段；revision 由乐观锁处理；敏感及权限审计写失败回滚业务 |
| Flyway 迁移治理 | 已发布 V1–V5 不改，当前迁移至 V10；真实迁移由发布流程执行，不在代码审查任务连接生产执行 |
| 写入白名单与版本 | 菜单/按钮/字典/角色/成员使用 DTO；编辑提交 revision 或 membershipRevision，更新0行拒绝并回滚。用户启停/移出、角色删除以 expectedRevision 比较客户端版本；改密同时比较账号/成员版本 |
| 授权上界与精确绑定 | 公司有效角色事实源；菜单/按钮分别绑定；永久角色控制再次授权上界；User读取已在SQL消费范围 |
| Redis TTL 与原子操作 | 普通缓存可抖动；凭据及授权截止缓存精确期限；刷新族用CAS，票据/注销原子消费；Redisson仅用于锁场景 |
| 提交后缓存一致性 | 数据库epoch为权威，持久outbox与业务同事务；Redis投递在事务外，可幂等重试；投递围栏、受控预览重放、低基数指标及错误分类已实现；完成记录留存策略待制定 |
| 全表写/批量上限 | BlockAttack兜底；通用批量常量1000，授权资源单批500，接口分页200、插件500。框架插件不是所有手写SQL安全性的证明 |
| 事务内禁止外部调用 | 当前认证RPC在数据库事务外，outbox投递Redis不持有业务事务；新增流程仍需检查调用链 |
| 外部超时与降级 | Feign connect3s/read8s，网关connect3s/response15s；降级与临时RPC失败不能被解释为身份已撤销 |
| 响应与文档 | OpenAPI3注解、Result/错误码、时间格式与不透明String ID；long计数/TTL保持JSON number |

## Robot 适配

| 项 | 当前约定 | 适配原因 |
| --- | --- | --- |
| 技术基线 | JDK17编译目标，Boot/Cloud/SCA版本由BOM锁定 | Robot项目基线；不宣称是当前最新或已经完成升级安全验收 |
| 成功/分页 | code=0，message与msg同值；data.list/total/page/pageSize | 对应Robot_Admin合同；与固定成功2000/records的profile区分 |
| HTTP风格 | GET查询、POST新增、PUT更新、DELETE软删 | Robot_Admin既有REST路径；真实可用路径见api-contract |
| 时间 | DATETIME/LocalDateTime，yyyy-MM-dd HH:mm:ss | 当前MySQL与前端格式；不附加时区，环境必须约定服务器时区 |
| 模块 | 单服务内api/entity包 + 独立跨服务api-system | 当前项目粒度；common/API禁止实现反向依赖仍执行 |
| 认证 | Sa-Token封装 + token Session +刷新族 | 不使用jh4j认证实现；身份隔离和撤销须以真实行为测试证明 |
| 端口/库 | 8080/9201/9202，system拥有robot_cloud | Robot命名与单系统库起步；不宣称已经拆分每个业务数据库 |

## 历史执行和验证记录（2026-10-09，正式安装前）

本任务复用 run-id `2d6d4ce8-6fdf-4e1a-a76a-6d48e00d86e4`，本机 Kit task 判定本次纯后端目标没有适用前端 Skill；BD task 因 Robot 未安装其项目 Skill/standards 记录 gap。此次读取同包规范源并执行真实校验，不能把 task gap 改写为安装完整或工作流覆盖通过。

当前完整 CI 入口 `scripts/verify.sh --mysql-upgrade` 实际成功：14 模块、56 套 Java XML 报告、308 测试，失败/错误/跳过均为0；含3项隔离真实 MySQL 已有库升级测试。最终 Boot JAR 的17个Mapper及68个基础statement另行组装通过。XML、日志、源码与制品哈希保存于 [本轮证据](../../../output/robot-cloud-iteration-2026-10-09/evidence.json)。数量只描述此次执行，后续修改需重新验收。

| 校验范围 | 当前实际结果与解释 |
| --- | --- |
| BD 全规则 | 74 errors、0 warning；B1 70条、B17 4条，原始回执保留 |
| BD 适配选择 | 除 B1/B17 的30条规则，0 error/0 warning；不宣称32条全部通过 |
| B1 | 扫描器固定识别平台 PreAuthorize，Robot 使用 Sa 注解、公共路径和内部信任；由端点合同、MVC/架构测试及专门身份负例补充验证，不能给真实未鉴权路径自动豁免 |
| B17 | 三条 MyBatis-Plus 逻辑删除字面误报；一条隔离测试故意 DROP TABLE 模拟审计表故障，与生产 DDL 禁令的适用位置不同 |
| B23 / B9 / B11 | 原角色服务高耦合已拆分；UserService职责收敛至489行；打包探针职责拆分，当前实际扫描不再报这些项 |
| 托管 CI | workflow 已接同一门禁并固定Actions SHA；仓库已接 GitHub/Gitee，托管执行结果以对应提交的 Actions 记录为准 |

正式扫描与 status 均使用同一 run-id。全量回执失败和安装 gap 均如实留存；适配后的扫描通过只能证明其规则范围。方法改名、文档注解与测试改善没有通过增加伪事务或跳过业务测试来规避检查。

真实运行结果按 MySQL 升级、HTTP 写命令、双 System 缓存故障、重启恢复和旧权限缓存回填分别登记。单主 Redis 成功不推导 HA/cluster、旧会话快照恢复或生产容量通过。当前临时授权产品模型、全局目录写权限归属、字段策略仍需产品及部署验收。

相关操作见 [部署](deployment.md)、[API](api-contract.md)、[缓存与字典](cache-and-dictionary-contract.md)。

原生同runId聚合status实际为`executionStatus=failed`、`validationStatus=stale`、`stale/planStale=true`、`outdatedChecker=false`，早期回执仍保留。最终四份执行收据分别记录完整输入快照、检查期间无变化；项目静态适配/精确语义门通过不覆盖聚合status，也不把其J门pending改成通过。
