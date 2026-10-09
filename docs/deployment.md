# 部署与运维

本文描述仓库当前配置与上线步骤。依赖版本以 `robot-dependencies/pom.xml` 为准；基础设施版本、生产容量与高可用方案需由部署环境单独锁定和验收。

## 1. 端口与网络

| 服务 | 默认端口 | 暴露范围 |
|---|---|---|
| robot-gateway | 8080 | 前端 API 入口，由反向代理提供 TLS |
| robot-auth | 9201 | 应用内网 |
| robot-system | 9202 | 应用内网 |
| MySQL | 3306 | 应用与迁移账号所在网段 |
| Redis | 6379 | 应用网段 |
| Nacos | 8848 / 9848 | 控制台/客户端访问；按所选服务端版本确认其他端口 |
| Sentinel Dashboard | 8858 | 可选运维入口 |

网关仅配置 `/auth/**`、`/sys/**`、`/account/**` 业务路由，拒绝 `/inner/**` 并清洗外部请求的全部 `X-Inner-*` 头。网关只检查 Bearer 头存在性与前缀；会话、刷新族和主体有效性由 auth/system 校验。不能靠网关替代后端鉴权，也不能把 9201/9202 暴露到公网。

## 2. 配置与环境变量

| 配置项 | 仓库默认 | 部署要求 |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `local` | `nacos` profile 开启注册发现；按实际环境选择 |
| `NACOS_SERVER_ADDR` | `127.0.0.1:8848` | 使用可达地址；按环境设置 `NACOS_NAMESPACE` |
| `ROBOT_ENV` | `local` | dev/test/prod 显式设置；用于自定义 Robot Redis key 的环境段 |
| `ROBOT_INNER_SECRET` | `robot-inner-dev-secret` | 开发占位值；生产显式替换为随机密钥，所有可信调用方与 system 一致 |
| `ROBOT_AUTH_URI` / `ROBOT_SYSTEM_URI` | local：`http://127.0.0.1:9201` / `http://127.0.0.1:9202` | 网关目标；nacos 默认 `lb://robot-auth` / `lb://robot-system` |
| `ROBOT_SYSTEM_URL` | local：`http://127.0.0.1:9202` | auth 的 `remoteSystemService` Feign 直连地址，仅 local 配置使用 |
| `MYSQL_HOST` / `MYSQL_PORT` / `MYSQL_DATABASE` | `127.0.0.1` / `3306` / `robot_cloud` | system 数据源 |
| `MYSQL_USERNAME` / `MYSQL_PASSWORD` | `root` / `root` | 生产使用专用账号，密码来自部署密钥配置 |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_DATABASE` | `127.0.0.1` / `6379` / `0` | auth/system 指向相同会话存储；不同环境使用独立实例或 database |
| `REDIS_PASSWORD` | 空 | 生产显式配置访问凭据并限制网络 |
| `ROBOT_CORS_ORIGINS` | `http://localhost:*` | 网关生产收敛为 Robot Admin 的准确 origin |
| `SENTINEL_DASHBOARD` | `127.0.0.1:8858` | 按是否部署控制台设置 |

`ROBOT_ENV` 由 JVM 属性 `robot.env` 或环境变量读取，不是 Nacos 中的同名 YAML 属性。它只隔离自定义 Robot key，不能据此认定 Sa-Token 自身 key 已按环境隔离。auth/system 必须使用一致的 Redis 连接和 Sa-Token 配置。

Redis 的 username/password 为空或仅空白时归一为 `null`，避免 Redisson 对无密码 Redis 发送空 `AUTH`；非空凭据原样保留，包括两端空格。该处理不替代生产凭据与 ACL 配置。

仓库仍保留内部密钥开发默认值；配置为空时内部鉴权拒绝请求，但**未配置变量仍会使用开发默认值**。生产启动前检查实际生效配置是否已替换，不在日志、镜像、命令历史中输出密钥。当前凭据是共享 bearer secret：持有它的服务进入同一信任域，不具备每个服务独立身份或 mTLS。密钥轮换需协调调用方与提供方；当前没有双密钥过渡机制。

| profile | 注册/配置与寻址 |
|---|---|
| `local`（默认） | 不导入 Nacos 配置，关闭 config/discovery；网关 HTTP 直连 auth/system，auth 的 Feign HTTP 直连 system；关闭 Sentinel 及 Sentinel/Nacos discovery 健康检查 |
| `nacos` | 导入各服务配置并开启 config/discovery；网关默认使用 `lb://`，auth 按服务名调用 system |

三个服务已引入 Nacos Config starter，配置导入仅在 `application-nacos.yml`。dataId 为 `robot-gateway.yml`、`robot-auth.yml`、`robot-system.yml`，group 为 `ROBOT_CLOUD`；config 与 discovery 均配置 `NACOS_SERVER_ADDR`、`NACOS_NAMESPACE`。网关已引入 Sentinel Nacos datasource，规则 dataId 为 `robot-gateway-flow-rules`、group 为 `SENTINEL_GATEWAY_GROUP`。这些依赖与配置已具备，真实 Nacos 读取、注册和规则推送仍需独立验收。部署时核对最终生效配置。

`local` 的健康检查不会探测未启用的 Nacos/Sentinel；它也不证明其他 profile 下这些组件健康。

生产数据源应覆写仓库开发 JDBC URL 的 TLS 参数。MySQL DML 与迁移 DDL 账号分离；`spring.flyway.enabled` 默认开启，若流水线或 DBA 已负责迁移，应用部署显式设为 `false`，避免重复执行。

## 3. local 启动步骤

先准备已安装的 JDK 17 或 21，明确设置 `JAVA_HOME`；示例目录需替换为实际 JDK 根目录。macOS 的 `/usr/bin/java` 或 IDE 启动器不一定能找到该 JDK，提示无 Java runtime 时应检查 `JAVA_HOME` 和 IDE 的运行 JDK。

```bash
export JAVA_HOME=/path/to/jdk17-or-jdk21
"$JAVA_HOME/bin/java" -version
export SPRING_PROFILES_ACTIVE=local
export DEBUG=false
export ROBOT_ENV=local
```

1. **先启动 MySQL 和 Redis**，确认目标端口、账号与访问权限；local 无需 Nacos 或 Sentinel Dashboard。auth/system 依赖 Redis，system 还依赖 MySQL，缺少依赖不能完成启动和登录链路。
2. 新环境先创建空数据库。以下以默认 `MYSQL_DATABASE=robot_cloud` 为例；修改库名时同步数据源配置。已有非空库先检查表结构与 Flyway history，不要盲目 baseline。

   ```sql
   CREATE DATABASE robot_cloud CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
   ```

3. 从项目根目录执行 `mvn clean verify`，保留结果；Maven 使用所设置的 `JAVA_HOME`。按实际基础设施注入前表的 MySQL/Redis 配置和一致的内部密钥。
4. 在三个终端依次启动 system、auth、gateway；默认空库由 system 的 Flyway 执行迁移。

   ```bash
   "$JAVA_HOME/bin/java" -jar robot-services/robot-system/target/robot-system-1.0.0-SNAPSHOT.jar
   "$JAVA_HOME/bin/java" -jar robot-services/robot-auth/target/robot-auth-1.0.0-SNAPSHOT.jar
   "$JAVA_HOME/bin/java" -jar robot-gateway/target/robot-gateway-1.0.0-SNAPSHOT.jar
   ```

5. 分别检查 `http://127.0.0.1:9202/actuator/health`、`http://127.0.0.1:9201/actuator/health`、`http://127.0.0.1:8080/actuator/health`，再经网关验收登录与受保护接口。健康 UP 不等于登录、权限和缓存链路已通过。

### 可选 Docker 模板

以下模板仅用于独立开发环境，镜像标签是示例，未作为生产部署验证证据。实际使用前锁定补丁版本或 digest；默认密码和端口映射不能用于生产。

```yaml
services:
  mysql:
    image: mysql:8.0
    environment:
      MYSQL_DATABASE: robot_cloud
      MYSQL_ROOT_PASSWORD: root
    command: --character-set-server=utf8mb4 --collation-server=utf8mb4_general_ci
    ports: ["127.0.0.1:3306:3306"]
    volumes: ["mysql-data:/var/lib/mysql"]
  redis:
    image: redis:7-alpine
    command: redis-server --appendonly yes --maxmemory 512mb --maxmemory-policy noeviction
    ports: ["127.0.0.1:6379:6379"]
  nacos:
    image: nacos/nacos-server:v2.2.3
    environment:
      MODE: standalone
    ports: ["127.0.0.1:8848:8848", "127.0.0.1:9848:9848"]
volumes:
  mysql-data:
```

会话和刷新族使用 Redis；容量不足、驱逐或数据恢复都可能影响登录态。生产需验证持久化、内存策略和恢复流程，不能将上述单节点模板视为 HA 方案。

## 4. 本次升级：受控切换

本次 token Session、刷新族 CAS 与账号/成员 `securityVersion` 协议不兼容旧版账户 Session、明文 refresh payload 和旧 loginTicket。公开字段保持不变不代表旧会话可用。**必须要求用户重新登录，不能混跑新旧 auth/system 节点或盲目滚动回退。** 故障语义见 [认证会话生命周期](auth-session-lifecycle.md)。

1. 构建同一发布版本：执行 `mvn clean verify`，保存实际测试结果、制品摘要与生效配置清单。测试通过仅证明所执行范围。
2. 在真实 MySQL 的备份副本上执行 V6–V10 迁移与重复数据预检，核对 Flyway history、表结构、唯一约束和存量数据。预检与约束说明见 [缓存与字典契约](cache-and-dictionary-contract.md)。发现冲突先由业务负责人裁决，不自动删除授权关系。
3. 备份数据库及 Redis，明确停机窗口和恢复点；暂停外部登录、刷新和业务写入，排空在途请求，停止全部旧版 auth/system 节点。
4. 由唯一迁移执行方应用已审查的迁移。默认 `baseline-on-migrate=false` 拒绝非空无历史库，已有库仍需先核对基线；禁止改写已发布迁移或无审查修复 checksum。
5. 部署同一版本的 system、auth、gateway，核对所有实例的 API 契约、内部密钥与 Redis 环境。按已审批的环境隔离方案使旧会话失效，保留其他应用与环境数据；不使用整库清空替代精确处理。
6. 完成下列冒烟后开放流量，并通知客户端重新登录。客户端应合并同设备并行刷新，已消费的 refresh/loginTicket 不能自动重放签发。

回退也必须停止流量、统一切换节点并按已验证方案处理会话与数据库。V7–V9 增加或替换唯一约束，本轮没有证明旧应用能直接运行于迁移后的 schema；不能承诺“只回退镜像、数据库天然兼容”。

## 5. 验收与运行边界

| 验收项 | 必须观察的行为 |
|---|---|
| 登录与上下文 | 单公司登录、多公司激活、两设备分别保持公司 A/B；切换仅轮换当前设备 |
| 刷新/注销 | 并发刷新单一提交；旧 access 被拒绝；过期 access 可定位注销其尚有效刷新族 |
| 主体撤销 | 改密、账号/成员停用后旧 access/refresh 被拒绝，重新启用不恢复旧版本会话 |
| 内部信任 | 网关拒绝 `/inner/**`；直连 system 的缺失/错误密钥与不一致委托返回 403 |
| 权限/字典 | 提交后读到数据库新 epoch；Redis 通知失败可重试，不恢复旧授权；stale revision 拒绝 |
| 服务健康 | `/actuator/health`、注册发现、真实 Feign 调用、迁移状态与 Redis 连接可用 |

仓库内实际范围摘要见 [本轮验收](verification-2026-10-09.md)。[本轮 evidence.json](../../../output/robot-cloud-iteration-2026-10-09/evidence.json)是开发工作区回执，独立clone不自带；托管CI另留artifact。历史启动报告仅代表之前源码，不能替代本轮制品验证。

| 入口 | 证明范围 |
| --- | --- |
| `bash scripts/verify.sh` | clean verify、ArchUnit/行为/机器契约、SQL 清单、最终 JAR 的 17 个 Mapper 和每个 Mapper 的 4 个基础 statement |
| `bash scripts/verify.sh --mysql-upgrade` | 增加真实 MySQL 随机新建库；V5 存量数据→V6 旧 pending 通知→最新版本；对账列/唯一索引/校验和、保全原数据、拒绝重复基线和非空无历史库 |
| `scripts/runtime/` | 两 System 共享隔离 MySQL/Redis；真实 TCP 断网、Lua 应用后丢失/迟到 ACK、同预览并发重放、通知单调性；进程重启与旧缓存回填另由运行回执记录 |
| GitHub Actions | push/PR/manual 使用同一门禁，归档报告；配置 JDK 17/MySQL 8.0，实际是否执行以托管 job 为准 |

本机使用 JDK 21、MySQL 8.4.11、Redis 7.4.2。Flyway 9 对 MySQL 8.4 提示支持范围警告；执行成功不能据此承诺官方兼容。仓库已接入 GitHub/Gitee，托管 CI 结果以对应提交的 Actions 记录为准；目标 MySQL 8.0/JDK 17 运行、真实 Admin 页面、Nacos/Sentinel、Redis 主从/cluster/旧快照恢复需独立验收。机器数据库合同覆盖用户/角色聚合的六张业务表，不是所有表全部治理属性的认证。

升级验收只创建 `robot_verify_<随机ID>` 库并清理自己创建的库；只接受 loopback，不能指向已有业务数据库。默认不执行 `clean`/`repair`/自动 baseline。真实生产备份、容量、DDL 失败恢复仍需对应环境的演练。

逐族注销仅保存在 Redis，异步复制回退可能恢复旧族；数据库安全版本可阻断已发生版本变更的身份，但不能替代逐族注销持久性证明。上述外部 HTTP 并发检查不替代确定性 CAS 时序与 HA 验证。

## 6. 监控与运维待办

actuator 默认包含 health/info/prometheus，网关还包含 gateway。生产应限制这些端点以及 `doc.html`、`/v3/api-docs` 的网络访问；后端当前将相关路径列为免登录，不能只依赖业务鉴权。监控健康、连接池、请求延迟/错误率、Redis 内存与写入失败、MySQL 慢查询，阈值按压测基线确定。

### 缓存通知与告警

Outbox 默认每 5 秒扫描，失败指数退避并加入有限抖动，上限 300 秒；单轮有界读取。`robot.cache-outbox.enabled` 可禁用调度，`robot.cache-outbox.retry-delay-ms` 可调整扫描间隔。生产不要因短暂断网清空 pending 或回退 epoch。

| Prometheus 指标 | 观察目的 |
| --- | --- |
| `robot_cache_outbox_pending` / `due` | 未完成/已到期积压 |
| `robot_cache_outbox_failed_pending` / `retries_pending` / `max_attempts` | 重试范围与失败强度 |
| `robot_cache_outbox_oldest_age_seconds` | 最老 pending 年龄，体现处理延迟 |
| `robot_cache_outbox_snapshot_age_seconds` | 数据库快照新鲜度；抓取成功也可能是旧快照 |
| `robot_cache_outbox_inflight` | 当前实例在途投递 |
| `robot_cache_outbox_results_total{outcome}` | confirmed/retried/superseded/database_failure/replayed |
| `robot_cache_outbox_failures_total{category}` | 受控失败分类，无事件/用户/key高基数标签 |
| `robot_cache_outbox_delivery_seconds_*` | 投递耗时 |

积压 gauge 是各实例读取的同一个数据库事实，双实例查询应取 `max`，不能直接求和；每实例计数器可按职责汇总。年龄和积压阈值需依据业务允许延迟、扫描间隔和压测设置，避免把尚未刷新或采集失败的零值解释成健康。

处理流程：确认 Redis/数据库连接及容量→查询 `/sys/cache-outbox/summary` 和有界事件摘要→核对受控错误类别与 failureId→永久超管提交 `replay-preview`→人工确认 reason/事件状态→使用同一 planHash 提交 replay→检查追加审计与完成状态。预览变化返回40902，须重新检查，不绕过哈希。API 细节见 [接口契约](api-contract.md)。重放只重新安排通知，不再次执行用户或角色写命令。

**已完成 outbox 的保留期、归档/清理作业和容量告警规则需运维策略落地**，不得删除 pending 记录。权限 epoch 是数据库权威，Redis 权限版本只单调推进；字典缓存按数据库版本隔离。业务拒绝和通知处理失败应只记录受控类别、traceId/failureId，不能输出 token、password、共享密钥、缓存 payload 或原始下游报文。

### 隔离故障演练

仅在独立新建 `robot_iteration_*` 库、高位 loopback 端口和独立 Redis 中使用 `scripts/runtime/redis-fault-proxy.py`；代理只解析协议、输出数量，不记录命令载荷。Auth 直连 Redis，两 System 经代理连接；`offline` 关闭现存连接并拒绝重连，`hold_ack/drop_ack` 对已经执行成功的单 key 权限 Lua 回包门控。20 秒 Redis timeout 仅用于确定性迟到 ACK 演练，不能直接用作生产超时建议。

`cache-outbox-probe.py --help` 列出前置条件。凭据从 `PROBE_USERNAME/PROBE_PASSWORD/PROBE_CONTEXT_ID` 环境传入；`--confirm-isolated-fixture` 与明确权限 key 为必要参数。SQL fixture 仅在同事务推进 epoch 并入队，不等同于“Redis 断网时用户 MVC 写命令成功”；完整业务回滚由 Spring/数据库事务测试另行证明。进程管理和重启由演练控制者负责，脚本不杀其他服务。结果需保留检查清单、制品 SHA 和演练边界。

应用异常日志只记录固定错误类别、业务码和traceId，不展开下游消息、堆栈或原始URI；技术异常响应为固定提示，业务/字段校验保留面向用户的规则消息。X-Trace-Id仅沿用 `[A-Za-z0-9_-]{1,64}`，非法输入重新生成并回传。格式约束不代表能够阻止客户端主动把合法形态的敏感值当作标识。

显式使用 `DEBUG=false`，收敛 `logging.level` 与第三方HTTP/Redis诊断日志；应用处理器的安全输出不控制第三方组件在DEBUG/TRACE下记录请求/响应载荷。日志按 `traceId` 关联，完整链路与并发效果仍需部署验收。Nacos 控制台鉴权、环境命名空间、Redis ACL/网络、备份恢复与凭据轮换由生产运行方案落实。
