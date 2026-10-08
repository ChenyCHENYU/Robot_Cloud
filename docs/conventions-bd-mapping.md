# wl-skills-bd 规范映射与偏差登记

> 目的：说明本框架对团队 `wl-skills-bd`（v0.32，B1~B32 / standards 01~30）通用要求的**采纳方式**，以及因 Robot 生态契约差异产生的**显式偏差与理由**。
> 原则：通用不变式（分层/租户/软删/数据安全/命名/迁移治理）**全量保留**；profile 相关（jh4j 技术栈与响应码）按 Robot_Admin 前端契约**有意适配**。

## 1. 不变式采纳清单

| bd 不变式 / 规则 | 本工程落点 |
|---|---|
| 分层 Controller → Service → Mapper，禁止跨层（standards/02） | 子域优先包结构；Controller 无业务逻辑；Service 无 SQL；Mapper 仅接口 + XML |
| Service 默认直接类，多实现才拆 port/impl（02） | `UserService`/`RoleService`/`MenuService` 等直接类；`service/context/` 为跨子域装配器 |
| 租户只来自 AuthUtil，SQL 显式 COMPANY_ID 或统一拦截器证据（不变式 1 / 11） | `RobotTenantLineHandler`（MP TenantLineInnerInterceptor）+ `AuthUtil.getLoginCompanyId()`；未登录恒假防泄露 |
| 软删事实源：DELETE_FLAG 1=有效 0=删除（不变式 2） | 语义一致；MP 逻辑删除全局启用，`removeById` 统一软删 |
| 八列治理基线（12 §3） | `BaseEntity` + DDL 全表落地（ID+COMPANY_ID+CREATE/UPDATE_USER_NO/DATE_TIME+DELETE_FLAG+REVISION） |
| 数据库列禁 is_，统一 *_FLAG（12 §2） | 全部 `*_FLAG`：status_flag/primary_flag/visible_flag/keep_alive_flag |
| MySQL 统一 lower_snake_case（12 §2） | DDL 全小写下划线 |
| Flyway 唯一迁移写入者、已发布不可变、expand-contract（12 §5/6） | V1/V2 迁移；变更流程文档化（database-design §6） |
| DDL 只生成不由工具执行（不变式 3） | 迁移在流水线/DBA 窗口执行，代码仓库只维护 SQL 文件 |
| Redis 必带 TTL、锁用 Redisson、禁 KEYS/FLUSH、大 Key UNLINK（20 / B13~B15） | `RedisHelper`（无 TTL API 不存在 + 抖动 + 空值占位）、`LockTemplate`（watchdog + 安全释放）、evict 走 unlink |
| 物理删禁令 / 全表写 WHERE / saveBatch ≤ 1000（B16/21） | 逻辑删除全局启用；`BlockAttackInnerInterceptor`；`BATCH_WRITE_LIMIT=1000` |
| 事务内禁 MQ/HTTP（不变式 9 / B20） | 无 MQ 依赖（天然满足）；Feign 调用均在事务外 |
| 外部调用必须超时（22） | Feign connect 3s / read 8s；网关 httpclient 3s/15s；登录链路不自动重试 |
| 新代码统一 OpenAPI 3（13） | springdoc + Knife4j，全 Controller @Tag/@Operation |
| ServiceAssert + ServiceException + 业务码字典 + 全局 Advice（08） | `ServiceAssert` / `ServiceException` / `ResultCode` / `GlobalExceptionHandler` |
| SLF4J 占位符 + traceId + 异常响应必带 traceId（09） | 统一日志 pattern；异常 Result 携带 traceId |
| 敏感写二次确认 / 审计（21） | 临时授权 reason 必填 + 30 天上限；`sys_permission_audit_log` 只追加；PermissionChangeRecorder 统一入口覆盖 15 类权限动作（grant/rebind/save/update/delete × user/role/menu/button + temp_authorize） |
| 父 BOM 依赖版本门（B27） | `robot-dependencies` BOM 唯一版本登记处 |
| 布尔禁 isXxx Java 属性（27 命名治理） | `xxxFlag` 命名一致（statusFlag 等） |
| 框架零业务耦合（复用性要求） | common 模块不含业务默认值：租户白名单由服务 yml 声明（TenantProperties 默认空）、业务常量在 robot-system `SystemConstants`、业务 Redis 键在 `AuthKeys`/`SystemKeys` 归属各服务、实体基类拆分 BaseEntity/TenantEntity |
| DTO 无参构造 + 序列化安全（B32） | DTO 全部 `@Data @NoArgsConstructor`；跨服务 DTO Serializable |
| 配置三层分层 / 环境隔离 / Secret 不入库（24/25） | local/nacos profile + 环境变量密钥 + .gitignore 拦截 |
| 变更审查 / 生产零写入护栏（30 / 21 §8） | 发布流程 DBA 窗口 + DDL 预检报告（deployment §5） |
| 权限码 + 越权防护（11） | `@SaCheckPermission` 全量覆盖；角色挂载校验本公司；权限码 30m 缓存 + 版本键惰性失效（见下） |
| 持久 Key 白名单（20 §2） | `robot:{env}:system:perm-version`（权限缓存版本，7 天 TTL 滚动续期，容量 O(1)，责任人：框架组；版本回落仅触发一次缓存重建，不影响正确性） |

## 2. 显式偏差登记

| # | bd 口径 | 本工程口径 | 理由 |
|---|---|---|---|
| D1 | 技术栈 Java 8 / Boot 2 / jh4j-cloud 3.1（`jh4j3-openapi3` profile） | JDK 17 / Boot 3.2.12 / SCA 2023.0.3.3 | Robot 生态新建工程无存量约束；用户明确要求 Spring Cloud Alibaba 官方最佳实践；bd profile 服务于 jh4j 存量体系 |
| D2 | 成功码 2000，envelope `code/message/data`，分页 `data.records/total` | 成功码 0，envelope 增加 `msg` 兼容字段，分页 `data.list/total/page/pageSize` | Robot_Admin 前端既定契约（`BUSINESS_SUCCESS_CODES=[200,0]`、activate 强校验 '0'、PageResult=list/total）；任务指令「对照前端项目」优先 |
| D3 | API 风格 POST `queryPage` / `save` / `updateById` / `deleteById/{id}` | REST 风格 GET 分页 / POST / PUT/{id} / DELETE/{id}，分页参数 `page/pageSize` | 同上：Robot_Admin 页面 data.ts 全部按 REST + page/pageSize 调用 |
| D4 | 审计时间列 VARCHAR(19) | DATETIME + LocalDateTime | VARCHAR(19) 是 jh4j 为 Oracle 兼容的历史包袱；新库纯 MySQL，DATETIME 支持范围查询与索引语义 |
| D5 | API/Entity 独立模块 `xxx-api/xxx-entity`（多模块 3 拆） | 单服务模块 + 独立 `robot-api-system` 契约模块 | bd 02 允许「小项目变种：api/entity 合并进 xxx-service，包路径保持一致」；跨服务契约仍独立成模块（对应 wl-apis 角色） |
| D6 | 认证组件 AuthUtil/jh4j 内建 | Sa-Token（`AuthUtil`/`AuthHolder` 隔离 StpUtil） | Boot 3 技术栈下无 jh4j 内建；Sa-Token 为 SCA 生态社区主流，语义同构（详见 tech-selection §2.5） |
| D7 | 业务中心×端口×数据库集群映射表（wl-* 工程） | robot 生态端口段 9201~9299 + 单库起步 | 映射表是 wl/jhict 体系约定；Robot 生态独立端口规划（deployment §1），拆库演进路线保留 |

## 3. 与 wl-skills-bd 工具链的协作

- 本工程包结构与命名未按 `com.jhict.*`，因此 `wl-skills-bd` 的 service-codegen / B 规则扫描**不直接适用**于代码生成；其数据安全红线（B13~B23）已通过框架层机器化（RedisHelper/LockTemplate/BlockAttack/逻辑删除）替代兜底。
- 若后续纳入 bd 管控：将本文档 D1~D7 作为 waiver 登记输入，`profile.local` 可按 `docs/api-contract.md` 契约扩展自定义 profile（成功码 0 / REST 风格 / DATETIME）。
- 数据库事实源对账：`docs/database-design.md` 即 `docs/db-spec` 角色，表结构变更先改文档再写迁移（与 standards/29 流程一致）。

## 4. 验证状态（诚实声明）

| 验证 | 状态 |
|---|---|
| 14 模块 `mvn clean install` | ✅ 已执行全绿 |
| 25 个单元/装配测试 | ✅ 全部通过 |
| 运行时链路（登录/Flyway 对真实 MySQL） | ⏳ 本机无 MySQL/Redis/Docker 实例，按 deployment.md 在基础设施环境执行冒烟清单 |
