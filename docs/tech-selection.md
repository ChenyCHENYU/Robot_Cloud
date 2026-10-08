# 技术选型论证（综合对比）

> 目标：为 Robot 中后台（管理域、团队规模中小、与 Robot_Admin 前端配套）选出**官方维护、社区验证、团队规范兼容**的技术栈。每个决策给出候选对比与结论。

---

## 1. 基线版本矩阵（Spring Cloud Alibaba 官方对齐）

| 组件 | 版本 | 依据 |
|---|---|---|
| JDK | 17（LTS） | SCA 2023.x 要求 Boot 3 → JDK 17 起；本机 Zulu 21 运行验证通过 |
| Spring Boot | 3.2.12 | SCA 2023.0.3.3 官方矩阵指定 Boot 3.2.x 的末版（安全补丁齐全） |
| Spring Cloud | 2023.0.3 | 同上官方矩阵 |
| Spring Cloud Alibaba | 2023.0.3.3 | 当前 2023 分支最新（Maven Central 确认），配套 Nacos client 2.x / Sentinel 1.8.8 |

**为什么不是更新的 Boot 3.4/3.5？** SCA 稳定毕业版本按官方矩阵与 Boot 3.2 对齐；追新 Boot 需要 SCA 2025.x 分支（社区验证不足）。框架项目优先**矩阵内最新**而非绝对最新。

**为什么不是 jh4j-cloud 3.1（Java 8）？** wl-skills-bd 的 `jh4j3-openapi3` profile 服务于 jh4j 存量体系（Java 8 / Boot 2）。本工程是 Robot 生态**新建框架**，无存量约束；官方与社区当前最佳实践均为 Boot 3 + JDK 17+（虚拟线程、_records、安全支持周期）。bd 包的**通用不变式**（分层/租户/软删/数据安全/命名）全部保留，profile 相关差异显式登记于 conventions-bd-mapping.md。

---

## 2. 微服务组件

### 2.1 注册与配置中心：Nacos（✅ 选定）

| 候选 | 优点 | 弃选原因 |
|---|---|---|
| **Nacos** | SCA 亲儿子；注册+配置二合一；控制台成熟；AP/CP 可切 | — |
| Eureka | 简单 | 2.x 维护停滞；无配置中心 |
| Consul | 多数据中心 | 运维成本高；与 SCA 集成非一等公民 |
| Zookeeper | 强一致 | 运维重；CP 场景用 Nacos raft 模式可覆盖 |

本地联调友好性设计：`profile=local` 时 discovery 关闭，服务可脱离 Nacos 独立启动。

### 2.2 网关：Spring Cloud Gateway（✅ 选定）

| 候选 | 优点 | 弃选原因 |
|---|---|---|
| **SCG** | Spring 官方；WebFlux 高性能；过滤器生态 | — |
| Zuul 1 | — | 阻塞 IO，已淘汰 |
| Nginx+Lua | 极致性能 | 业务网关逻辑（鉴权/清洗/限流联动）写 Lua 维护成本高 |

### 2.3 限流熔断：Sentinel（✅ 选定）

| 候选 | 优点 | 弃选原因 |
|---|---|---|
| **Sentinel** | SCA 一等公民；网关流控/热点/系统自适应；控制台推规则 | — |
| Resilience4j | 轻量函数式 | 无控制台/规则中心，运营侧不可运营 |
| Hystrix | — | 停止维护 |

### 2.4 服务调用：OpenFeign + LoadBalancer（✅ 选定）

声明式契约集中在 `robot-api-system`；配套降级工厂与显式超时（connect 3s / read 8s）；登录链路默认**不重试**（防凭据尝试放大）。

### 2.5 认证：Sa-Token（✅ 选定）

| 候选 | 优点 | 弃选原因 |
|---|---|---|
| **Sa-Token** | 国产社区主流（30k+ star）；登录态/权限/会话开箱；Redis 集成天然共享给网关与服务；`StpUtil` 语义与 bd `AuthUtil` 模式同构 | — |
| Spring Security | 标准完备 | 中后台 RBAC 场景配置面大；网关共享会话需自建大量胶水 |
| JWT 自研 | 无状态简单 | 注销/顶替/续期全要自己造；刷新轮换与多点登录控制成本高 |

关键适配：`token-name=Authorization` + `token-prefix=Bearer`（前端 request-core 契约）；会话 30 分钟；`is-read-cookie=false`（纯头模式，防 CSRF 面）。

### 2.6 ORM：MyBatis-Plus（✅ 选定）

bd 体系标准（SQL 可审计、复杂查询可控），且提供本框架依赖的四件能力：逻辑删除、乐观锁、自动填充、**多租户行级拦截**（bd standards/11 认可的统一拦截器路线）。候选 JPA/Hibernate 弱在 SQL 不可控与团队规范不匹配。

> 版本注意：3.5.9+ 分页插件拆分到 `mybatis-plus-jsqlparser`，BOM 已同时登记。

---

## 3. 数据与缓存

### 3.1 数据库：MySQL 8.0（✅ 选定，utf8mb4）

| 候选 | 结论 |
|---|---|
| **MySQL 8** | 团队既定；生态/工具链/DBA 熟悉度最优；窗口函数/CTE/降序索引已覆盖管理域需求 |
| PostgreSQL | 功能更强 | 运维栈与团队现状不匹配，收益集中在重度复杂查询场景 |
| Oracle | 计费/重 | bd 体系为兼容 Oracle 付出的 VARCHAR(19) 等历史包袱正是我们要规避的 |

工程约束（bd standards/12 全量落地）：lower_snake_case；禁 `is_` 列（统一 `*_flag`）；八列治理基线；索引按真实谓词建；Flyway 唯一迁移写入者、已发布不可变、expand-contract 演进。连接池 HikariCP（Boot 默认，管理域足够；分层读密集可演进 dynamic-datasource + 从库，BOM 已登记 4.3.1）。

### 3.2 缓存与分布式协调：Redis 7 + Redisson（✅ 选定）

用途矩阵：

| 用途 | 键 | TTL | 说明 |
|---|---|---|---|
| 登录会话 | satoken:* | 30m | 网关/服务共享校验 |
| 刷新令牌 | robot:{env}:auth:refresh-token:* | 7d | 一次性轮换 |
| 登录票据 | robot:{env}:auth:login-ticket:* | 5m | 一次性 |
| 权限码缓存 | robot:{env}:system:permissions:{co}:{uid} | 30m+抖动 | 变更 evict |
| 字典缓存 | robot:{env}:system:dict:{code} | 30m+抖动 | 空值占位防穿透 |
| 分布式锁 | robot:{env}:{svc}:lock:* | watchdog | Redisson RLock 唯一实现 |

候选对比：

| 候选 | 结论 |
|---|---|
| **Redis（Lettuce+Redisson）** | 会话/缓存/锁一站式；sa-token/redisson 生态成熟 |
| 本地 Caffeine | 管理域多实例部署下一致性成本 > 收益；保留为二级缓存演进位 |
| etcd/ZK 锁 | 引入第二套协调组件，运维不经济 |

强制规范（B13~B15）：`RedisHelper` 层面**不存在无 TTL 写入 API**；TTL 抖动 0~60s；`unlink` 删除；禁 KEYS */FLUSH*；锁模板内置 watchdog 续期与 `isHeldByCurrentThread` 安全释放。

### 3.3 API 文档：springdoc 2.3 + Knife4j 4.5（✅ 选定）

OpenAPI 3（bd standards/13 新代码强制）；Knife4j 增强中文化调试 UI。**版本陷阱登记**：springdoc 2.6+ 需 Boot 3.4，本工程锁 2.3.0 与 Boot 3.2 匹配（升级 Boot 时同步评估）。

---

## 4. 工程化

| 项 | 选定 | 说明 |
|---|---|---|
| 构建 | Maven 多模块 | BOM 单点版本治理（robot-dependencies，对应 bd B27 父 BOM 门） |
| Lombok | 1.18.38 | 注解处理器统一在根 POM |
| 工具库 | Hutool 5.8 | ID/JSON 工具；避免重复造轮 |
| 测试 | JUnit5 + Mockito + spring-boot-starter-test | 25 用例：契约/安全/租户/ID/数据范围 + 两服务上下文装配冒烟（H2） |
| 监控 | actuator + prometheus | `/actuator/prometheus`；网关另暴露 gateway 指标 |

---

## 5. 摘要决策表

| 领域 | 决策 | 一句话理由 |
|---|---|---|
| 基线 | Boot 3.2.12 / SC 2023.0.3 / SCA 2023.0.3.3 / JDK 17 | SCA 官方矩阵内最新稳定 |
| 注册配置 | Nacos | SCA 一等公民，二合一 |
| 网关 | SCG + Sentinel | 官方组合，业务网关逻辑集中 |
| 认证 | Sa-Token（Redis 共享） | 契约同构 bd AuthUtil，双 Token 轮换易实现 |
| ORM | MyBatis-Plus | bd 标准 + 租户/审计/软删/乐观锁四件套 |
| DB | MySQL 8 utf8mb4 | 团队既定，规范全落地 |
| 缓存 | Redis + Redisson | TTL/锁规范机器化强制 |
| 文档 | springdoc 2.3 + Knife4j 4.5 | OpenAPI 3 强制（bd 13） |
| ID | 不透明 String(64) | bd idPolicy；防遍历 |

---

## 6. 明确不做（本期）与理由

| 项 | 理由 |
|---|---|
| Seata 默认启用 | 当前唯一跨服务调用是只读供给（auth→system），无跨服务写事务；引入即增加运维面。BOM 已登记版本，出现跨服务写时按官方接入文档启用（`seata.enabled=false` 起步） |
| RocketMQ 默认引入 | 管理域暂无异步事件诉求；bd 规则「事务内禁发 MQ」在无 MQ 时天然满足。登记版本，事件化改造时启用 |
| SkyWalking | traceId 已贯通日志与响应， span 级追踪待微服务数量上规模后按需接 Agent |
| 多数据源/分库 | 单库 robot_cloud 起步；按业务中心扩张时再拆（对应 bd 02 业务中心×数据库集群映射） |
| Docker Compose 内置 | 本机无 Docker；提供 deployment.md 中的 compose 模板供有 Docker 环境使用 |
