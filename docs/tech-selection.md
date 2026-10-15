# 技术选型与验证边界

Robot Cloud 为 Robot Admin 提供认证与系统管理能力。当前以 gateway、auth、system 三个服务起步，契约集中在 `robot-api-system`，公共能力按 core/web/security/redis/mybatis 分模块。拆分依据是职责与部署需求；新增模块应先确认数据归属、调用量和运维成本，不以服务数量判断架构质量。

## 1. 仓库锁定版本

下表来自 `robot-dependencies/pom.xml` 和本轮真实聚合 SBOM，描述 2026-10-14 的构建选择。Spring 官方矩阵将 Cloud 2025.1.2 及以后配对 Boot 4.1.x；本项目仍需完成最终质量门、Java 17 服务启动、真实 HTTP/页面链路和回退验收。参见 [Spring Cloud 兼容矩阵](https://spring.io/projects/spring-cloud/#overview)。

| 组件 | 仓库版本 |
|---|---|
| Java 编译基线 | 17 |
| Spring Boot | 4.1.1 |
| Spring Cloud | 2025.1.3 |
| Spring Cloud Alibaba | 2025.1.0.0 |
| MyBatis-Plus / jsqlparser 模块 | 3.5.17 / JSqlParser 5.2 |
| Sa-Token | 1.46.0，使用 Boot 4 starter |
| Redisson / Spring Data 适配器 | 4.8.0 / `redisson-spring-data-41` |
| springdoc | 3.1.1，单一 OpenAPI UI |
| Jackson 2 / Jackson 3 | 2.21.7 / 3.1.7；共享 annotations 2.21 |
| Tomcat | 11.0.26 |
| Flyway / MySQL Connector/J | 12.4.0 / 9.7.0 |
| Lombok / Hutool | 1.18.38 / 5.8.38 |

MySQL 8、Redis 7 是部署目标；Nacos、Sentinel 服务端版本与基础设施补丁版本不由此 BOM 锁定。已解析客户端为 Nacos 3.1.1、Sentinel 1.8.9，不代表对应服务端已验收。

构建和验证固定使用 **Java 17** 与 `./mvnw`。Maven Enforcer 接受 Java `[17,18)`、Maven `[3.9.16,4)`；Wrapper 3.3.4 下载 Maven 3.9.16，发行 ZIP 的 SHA-256 固定为 `5af3b743dd8b876b5c45da33b676251e5f1687712644abb4ee519ca56e1d89ce`。Boot 官方最低要求为 Java 17、Maven 3.6.3，本仓库采用更窄的验证基线；其他 JDK 的历史测试不能代替此基线。参见 [Boot 系统要求](https://docs.spring.io/spring-boot/4.1/system-requirements.html) 和 [Wrapper 配置](../.mvn/wrapper/maven-wrapper.properties)。

Boot 4 默认使用 Jackson 3；本项目通过官方 `spring-boot-jackson2` 过渡模块保留既有 JSON/Redis 契约，并将 MVC `spring.http.converters.preferred-json-mapper`、WebFlux `spring.http.codecs.preferred-json-mapper` 均设为 `jackson2`。自定义序列化配置改用 Boot 4 的 `Jackson2ObjectMapperBuilderCustomizer`；Jackson 2 的 BOM 属性是 `jackson-2-bom.version`，Jackson 3 是 `jackson-bom.version`，需分别锁定和扫描。官方已弃用 Jackson 2 支持，后续完整迁移到 Jackson 3 必须验证字符串 ID、数值计数、空值清空与缓存类型往返，不能把当前过渡桥视为长期接口。参见 [Boot JSON 文档](https://docs.spring.io/spring-boot/4.1/reference/features/json.html)。

Redisson 4.8.0 当前制品解析到 `redisson-spring-data-41`；升级 starter 时同时核对 Spring Data 适配器和实际依赖树。本轮漏洞修复与许可证条件见 [供应链审查](../quality/supply-chain.md)。

`wl-skills-bd` 中的分层、租户隔离、审计、软删、数据安全与版本治理规则用于本工程审查；jh4j 平台、Java 8/Boot 2、平台专用 API 等约束需按适用性区分。映射见 [规范映射](conventions-bd-mapping.md)，具体工具检查结果以任务记录为准，文档不作“全量规范已通过”的承诺。

## 2. 服务与安全

| 选择 | 当前用途与取舍 |
|---|---|
| Nacos | 三服务均引入 discovery/config starter；仅 `nacos` profile 导入配置并开启注册发现，local 不依赖 Nacos；真实读取/注册仍待验收 |
| Spring Cloud Gateway | 响应式路由、Bearer 头检查、内部头清洗与 traceId 透传；没有在网关内实现真实会话/数据库权限验证 |
| Sentinel | 网关流控适配、阻塞响应与 Nacos datasource 已接入；local 关闭 Sentinel 和其健康检查，动态规则、容量阈值及故障行为待真实环境验收 |
| OpenFeign + LoadBalancer | auth local 通过 `ROBOT_SYSTEM_URL` 直连 system，nacos 按服务名调用；connect 3s/read 8s，默认不重试，circuitbreaker 当前关闭 |
| Sa-Token + Redis | Bearer access 校验与 token Session，配合应用实现的刷新族、CAS 轮换和主体安全版本 |

网关 local 默认通过 `ROBOT_AUTH_URI`、`ROBOT_SYSTEM_URI` 直连 9201/9202，nacos 默认使用 `lb://`。本地先启动 MySQL/Redis，并创建空数据库；Java 命令显式使用 `$JAVA_HOME/bin/java`，`JAVA_HOME` 与 IDE 均选择 Java 17。步骤见 [部署文档](deployment.md)。

采用 Sa-Token 是为复用会话与权限入口；刷新轮换、注销竞争和多公司上下文属于本工程协议，不能据此视为框架自动提供了完整 OAuth2/OIDC 能力。access 配置 30 分钟、refresh family 7 天、loginTicket 5 分钟；公司/成员快照属于每个 token Session，相同账号的不同设备可以保持不同公司。

刷新/切换以单 key Redis Lua CAS 提交，票据以原子读取并删除消费。受保护请求同时验证当前族及账号/成员 `securityVersion`，临时 RPC 故障阻断请求而保留族。旧会话协议不兼容本次实现，上线需全部节点受控切换并重新登录；故障语义见 [认证会话生命周期](auth-session-lifecycle.md) 与 [部署文档](deployment.md)。Redis HA 数据回退对仅存 Redis 的逐族注销仍有影响，尚未完成运行证明。

内部 `/inner/**` 采用共享 bearer secret 和经过校验的用户委托。Feign 拦截器只作用于 system 契约调用，网关清洗全部外部 `X-Inner-*` 头，system 验证服务凭据及委托一致性。持有共享密钥的服务属于同一信任域；当前没有 mTLS、每个服务独立身份、独立权限范围或双密钥轮换能力。生产必须替换 `robot-inner-dev-secret` 开发占位值并限制网络。

## 3. 数据与缓存

MyBatis-Plus 用于 SQL 查询、逻辑删除、乐观锁、审计填充与公司行级隔离。公共基础设施不能替代业务校验：显式跨公司查询与平台全局表仍需在 Service 验证可信身份、有效成员及资源归属。权限由当前有效角色和精确菜单/按钮绑定计算，普通角色不能通过目录中的通配码获得超管能力。

MySQL 是权威事实源。菜单拓扑写入通过数据库行锁串行化；修改使用客户端版本，更新0行不能报告成功。Flyway维护schema，V6–V10引入epoch/Outbox、安全版本、活跃唯一约束和重放审计。存量冲突不自动合并；默认不接管非空无历史库。此前真实隔离MySQL升级演练覆盖V5业务数据和V6旧pending通知到V10的保全、历史与约束；本轮Flyway12.4.0在真实MySQL8.4.11完成20项新建/升级/竞争验收及同制品启动；其他目标MySQL版本须独立验证，见[本轮验收](verification-2026-10-15.md)。详情见 [部署](deployment.md)与 [缓存与字典](cache-and-dictionary-contract.md)。

| Redis 用途 | 当前契约 |
|---|---|
| Sa-Token 映射及 token Session | auth/system 共享连接与配置；`ROBOT_ENV` 不自动隔离 Sa-Token 自身 key |
| 刷新族与 access/refresh 摘要索引 | `robot:{env}:auth:*`，精确 7 天 TTL；refresh 原文不存入索引 |
| loginTicket 摘要索引 | 精确 5 分钟 TTL，最终原子消费 |
| 权限缓存 | `permissions:{company}:{user}:db-v{epoch}`；最多 30 分钟且不越过临时授权边界，精确 TTL |
| 字典缓存 | 字典编码归一化并散列后拼数据库 epoch；30 分钟业务缓存 TTL，可含抖动 |
| 权限版本通知 | Redis 版本只单调推进，7 天 TTL；数据库 epoch 决定实际权限缓存版本 |
| 通用分布式锁 | `LockTemplate` 封装 Redisson；认证 CAS 和菜单数据库行锁不依赖它 |

事务内同步更新数据库 epoch 并写 outbox，独立调度在提交后通知 Redis，通知失败持久重试。权限/字典读取使用数据库版本，因此 Redis 失效通知不是撤权的唯一保障。已完成 outbox 的保留、归档与容量管理仍待运维策略。

`RedisHelper` 提供携带 TTL 的写入，业务缓存可抖动；认证凭据和权限时间边界使用精确 TTL。Jackson 缓存序列化只允许登记的 Robot 类型与基础容器；缓存载荷变更仍需验证实际类型往返及部署兼容性。锁、Lua 和内存单测通过不能证明 Redis cluster 或主从切换下的行为。

Redis 凭据绑定后将空/空白 username/password 归一为 `null`，避免 Redisson 对无密码 Redis 发送空 `AUTH`；非空值原样保留。Redis 7.4.2 已用于真实本地启动和 HTTP 链路验收，仍不代表 HA、ACL 所有组合或生产容量已验证。

## 4. 工程化与契约

| 能力 | 当前实现与边界 |
|---|---|
| 构建/版本 | Maven 多模块，`robot-dependencies` 管理依赖；CI 应保留实际 verify 结果与制品摘要 |
| HTTP 契约 | DTO 接收可写业务字段，VO 输出；ID 使用不透明 String，数值计数/TTL 保持 JSON number |
| 文档 | springdoc OpenAPI 3 与 Swagger UI，生产访问控制需部署配置落实 |
| 测试 | JUnit、Mockito、Spring MVC、真实 Sa-Token 内存 DAO、H2 集成与架构检查；按实际执行报告记录范围与结果 |
| 监控 | actuator/prometheus 与 traceId；生产阈值、链路效果及告警处置待部署验收 |

此前版本已有14模块、56套Java报告、308测试及真实MySQL升级/Mapper装配记录；这些属于升级前证据。当前定版候选统一入口为 `scripts/verify.sh`：强制 `clean verify -Probot-quality`，检查真实Checkstyle/PMD/SpotBugs XML、同次Spotless执行、逐类JaCoCo阈值，以及新生成SBOM的OSV/许可结果。**Boot4最终完整门禁、真实服务与Admin联调仍在执行，尚不宣布整体通过。** 托管CI结果以对应提交的Actions记录为准，运行边界见 [部署](deployment.md)。

Admin 必须随读模型保留 revision 并在修改时提交，不能由服务端补当前 revision 掩盖并发冲突。前后端联调需覆盖现代/兼容字段、按钮独立授权、字典 code/label/value、状态和冲突提示；后端测试不等于 Admin 页面联调通过。

## 5. 尚未实现的扩展

Seata 2.2.0、RocketMQ Spring 2.3.4 仅在 BOM 登记，本轮没有启用分布式事务或消息系统，也没有实现与这些组件的可靠协作协议。若出现跨服务写入或异步业务需求，先明确一致性、幂等、补偿和运维目标，再单独选型、实现与验证；不能通过增加 starter 就认定能力完成。

多数据源、读写分离、数据库拆分、二级本地缓存、mTLS 和 span 级链路追踪也不作为当前已实现能力。现有管理域从单库起步；后续拆分需基于明确的数据所有权和调用证据。性能、生产目标 MySQL 8.0、存量库升级、Nacos、Robot Admin 与 HA 验收完成前，不称该框架已达到生产最佳实践的完整证明。
