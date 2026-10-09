# wl-skills-bd 通用规范映射

本文记录当前实现与 Robot 协议的适配，不是全量合规证明。适用的是非平台定制的分层、权限、数据隔离、软删、治理字段、事务、缓存与迁移要求；jh4j/wl 固定坐标、Java 8 profile、响应码和目录命名不能直接套到 Robot。

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

## 执行和验证记录

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
