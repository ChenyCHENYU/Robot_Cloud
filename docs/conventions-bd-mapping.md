# wl-skills-bd 通用规范映射

本文记录当前实现与 Robot 协议的适配，不是全量合规证明。适用的是非平台定制的分层、权限、数据隔离、软删、治理字段、事务、缓存与迁移要求；jh4j/wl 固定坐标、Java 8 profile、响应码和目录命名不能直接套到 Robot。

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
