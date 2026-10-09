# 技术选型与验证边界

Robot Cloud 为 Robot Admin 提供认证与系统管理能力。当前以 gateway、auth、system 三个服务起步，契约集中在 `robot-api-system`，公共能力按 core/web/security/redis/mybatis 分模块。拆分依据是职责与部署需求；新增模块应先确认数据归属、调用量和运维成本，不以服务数量判断架构质量。

## 1. 仓库锁定版本

下表来自 `robot-dependencies/pom.xml`，描述当前构建选择，不表示最新稳定版本、完整安全评估或官方兼容性已获验证。升级时需重新核对对应版本的官方支持矩阵和发布说明，并完成构建、运行及回退验收。

| 组件 | 仓库版本 |
|---|---|
| Java 编译基线 | 17 |
| Spring Boot | 3.2.12 |
| Spring Cloud | 2023.0.3 |
| Spring Cloud Alibaba | 2023.0.3.3 |
| MyBatis-Plus / jsqlparser 模块 | 3.5.12 |
| Sa-Token | 1.44.0 |
| Redisson | 3.44.0 |
| springdoc | 2.3.0 |
| Knife4j | 4.5.0 |
| Lombok / Hutool | 1.18.38 / 5.8.38 |

MySQL 8、Redis 7 是部署目标；Nacos、Sentinel 服务端版本与基础设施补丁版本不由此 BOM 锁定。Java 17 编译、JDK 21 下的本轮测试运行与生产 JDK 支持范围应分别记录，不能互相代替。版本没有因文档更新而升级。

Redisson starter 排除默认 `redisson-spring-data-34`，显式使用 `redisson-spring-data-32`，二者版本均为 3.44.0，以适配本仓库 Boot 3.2 的 Spring Data Redis。已检查制品依赖；后续升级需同时检查此适配器，不能仅修改 starter 版本。

`wl-skills-bd` 中的分层、租户隔离、审计、软删、数据安全与版本治理规则用于本工程审查；jh4j 平台、Java 8/Boot 2、平台专用 API 等约束需按适用性区分。映射见 [规范映射](conventions-bd-mapping.md)，具体工具检查结果以任务记录为准，文档不作“全量规范已通过”的承诺。

## 2. 服务与安全

| 选择 | 当前用途与取舍 |
|---|---|
| Nacos | 三服务均引入 discovery/config starter；仅 `nacos` profile 导入配置并开启注册发现，local 不依赖 Nacos；真实读取/注册仍待验收 |
| Spring Cloud Gateway | 响应式路由、Bearer 头检查、内部头清洗与 traceId 透传；没有在网关内实现真实会话/数据库权限验证 |
| Sentinel | 网关流控适配、阻塞响应与 Nacos datasource 已接入；local 关闭 Sentinel 和其健康检查，动态规则、容量阈值及故障行为待真实环境验收 |
| OpenFeign + LoadBalancer | auth local 通过 `ROBOT_SYSTEM_URL` 直连 system，nacos 按服务名调用；connect 3s/read 8s，默认不重试，circuitbreaker 当前关闭 |
| Sa-Token + Redis | Bearer access 校验与 token Session，配合应用实现的刷新族、CAS 轮换和主体安全版本 |

网关 local 默认通过 `ROBOT_AUTH_URI`、`ROBOT_SYSTEM_URI` 直连 9201/9202，nacos 默认使用 `lb://`。本地先启动 MySQL/Redis，并创建空数据库；Java 命令显式使用 `$JAVA_HOME/bin/java`，JDK 17/21 目录及 IDE 运行 JDK 由开发者配置。步骤见 [部署文档](deployment.md)。

采用 Sa-Token 是为复用会话与权限入口；刷新轮换、注销竞争和多公司上下文属于本工程协议，不能据此视为框架自动提供了完整 OAuth2/OIDC 能力。access 配置 30 分钟、refresh family 7 天、loginTicket 5 分钟；公司/成员快照属于每个 token Session，相同账号的不同设备可以保持不同公司。

刷新/切换以单 key Redis Lua CAS 提交，票据以原子读取并删除消费。受保护请求同时验证当前族及账号/成员 `securityVersion`，临时 RPC 故障阻断请求而保留族。旧会话协议不兼容本次实现，上线需全部节点受控切换并重新登录；故障语义见 [认证会话生命周期](auth-session-lifecycle.md) 与 [部署文档](deployment.md)。Redis HA 数据回退对仅存 Redis 的逐族注销仍有影响，尚未完成运行证明。

内部 `/inner/**` 采用共享 bearer secret 和经过校验的用户委托。Feign 拦截器只作用于 system 契约调用，网关清洗全部外部 `X-Inner-*` 头，system 验证服务凭据及委托一致性。持有共享密钥的服务属于同一信任域；当前没有 mTLS、每个服务独立身份、独立权限范围或双密钥轮换能力。生产必须替换 `robot-inner-dev-secret` 开发占位值并限制网络。

## 3. 数据与缓存

MyBatis-Plus 用于 SQL 查询、逻辑删除、乐观锁、审计填充与公司行级隔离。公共基础设施不能替代业务校验：显式跨公司查询与平台全局表仍需在 Service 验证可信身份、有效成员及资源归属。权限由当前有效角色和精确菜单/按钮绑定计算，普通角色不能通过目录中的通配码获得超管能力。

MySQL 是权威事实源。菜单拓扑写入通过数据库行锁串行化；修改使用客户端版本，更新0行不能报告成功。Flyway维护schema，V6–V10引入epoch/Outbox、安全版本、活跃唯一约束和重放审计。存量冲突不自动合并；默认不接管非空无历史库。真实隔离MySQL升级演练覆盖V5业务数据和V6旧pending通知到V10的保全、历史与约束；目标MySQL8.0仍需远端或对应环境验收。Flyway9对本机MySQL8.4有支持范围警告，执行成功不代表官方兼容保证。详情见 [部署](deployment.md)与 [缓存与字典](cache-and-dictionary-contract.md)。

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
| 文档 | OpenAPI 3 + Knife4j，生产访问控制需部署配置落实；版本配对不在本文承诺兼容 |
| 测试 | JUnit 5、Mockito、Spring MVC、真实 Sa-Token 内存 DAO、H2 集成与架构检查；按实际执行报告记录范围与结果 |
| 监控 | actuator/prometheus 与 traceId；生产阈值、链路效果及告警处置待部署验收 |

本轮完整门禁已执行14模块、56套Java报告、308测试，失败/错误/跳过均为0，包含3项真实MySQL升级测试；最终制品17个Mapper/68个statement装配检查另行通过。双System缓存故障、新写命令HTTP、重启及旧缓存回填按实际运行回执登记，不能由离线测试推导生产或页面通过。托管CI已配置同一入口并接入GitHub/Gitee远程，实际执行结果以对应提交的Actions记录为准。验证范围、当前未验项和工作区回执说明见 [部署](deployment.md)。

Admin 必须随读模型保留 revision 并在修改时提交，不能由服务端补当前 revision 掩盖并发冲突。前后端联调需覆盖现代/兼容字段、按钮独立授权、字典 code/label/value、状态和冲突提示；后端测试不等于 Admin 页面联调通过。

## 5. 尚未实现的扩展

Seata 2.2.0、RocketMQ Spring 2.3.4 仅在 BOM 登记，本轮没有启用分布式事务或消息系统，也没有实现与这些组件的可靠协作协议。若出现跨服务写入或异步业务需求，先明确一致性、幂等、补偿和运维目标，再单独选型、实现与验证；不能通过增加 starter 就认定能力完成。

多数据源、读写分离、数据库拆分、二级本地缓存、mTLS 和 span 级链路追踪也不作为当前已实现能力。现有管理域从单库起步；后续拆分需基于明确的数据所有权和调用证据。性能、生产目标 MySQL 8.0、存量库升级、Nacos、Robot Admin 与 HA 验收完成前，不称该框架已达到生产最佳实践的完整证明。
