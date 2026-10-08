# 部署与运维

## 1. 端口规划

| 服务 | 端口 | 说明 |
|---|---|---|
| robot-gateway | 8080 | 唯一外网入口 |
| robot-auth | 9201 | 认证（内网） |
| robot-system | 9202 | 系统管理（内网） |
| MySQL | 3306 | |
| Redis | 6379 | |
| Nacos | 8848（控制台）/9848（gRPC） | |
| Sentinel Dashboard | 8858 | 可选 |

> 新增业务中心端口段建议 9203~9299 预留，遵循「一个业务中心一个服务 + 一个 api 契约模块」扩展模式。

## 2. 环境矩阵（配置分层：本地 yml < Nacos < 环境变量）

| 环境 | profile | Nacos | 说明 |
|---|---|---|---|
| local | `local`（默认） | 关闭 | 本机无 Nacos 也能起：`java -jar` 直接跑，MySQL/Redis 必须可达 |
| dev | `nacos` | `NACOS_SERVER_ADDR` | 注册发现 + `robot-{svc}.yml`(group `ROBOT_CLOUD`) |
| test | `nacos` | 同上 | 流水线执行 Flyway migrate |
| prod | `nacos` | 同上 | 密钥只走 Nacos 加密配置/环境变量；doc.html、actuator 细节屏蔽 |

### 关键环境变量

| 变量 | 说明 | 默认 |
|---|---|---|
| `ROBOT_ENV` | Redis Key 环境段（dev/test/prod 必设，防多环境共池互踩） | local |
| `ROBOT_INNER_SECRET` | /inner/** 服务间鉴权密钥：所有服务与 Feign 调用方必须一致；生产用 32+ 位随机值并纳入密钥轮换 | robot-inner-dev-secret |
| `ROBOT_CORS_ORIGINS` | 网关 CORS 白名单（逗号分隔 origin pattern），生产收敛为前端域名 | http://localhost:* |

启动示例（dev）：

```bash
SPRING_PROFILES_ACTIVE=nacos NACOS_SERVER_ADDR=10.0.0.5:8848 \
MYSQL_HOST=10.0.0.6 MYSQL_PASSWORD=*** REDIS_HOST=10.0.0.7 REDIS_PASSWORD=*** \
java -jar robot-system-1.0.0-SNAPSHOT.jar
```

## 3. 本地基础设施（docker-compose 模板）

```yaml
# docker-compose.yml（供具备 Docker 的环境使用）
services:
  mysql:
    image: mysql:8.0
    environment:
      MYSQL_DATABASE: robot_cloud
      MYSQL_ROOT_PASSWORD: root
    command: --character-set-server=utf8mb4 --collation-server=utf8mb4_general_ci
    ports: ["3306:3306"]
    volumes: ["mysql-data:/var/lib/mysql"]

  redis:
    image: redis:7-alpine
    command: redis-server --appendonly yes --maxmemory 512mb --maxmemory-policy allkeys-lru
    ports: ["6379:6379"]

  nacos:
    image: nacos/nacos-server:v2.2.3
    environment:
      MODE: standalone
    ports: ["8848:8848", "9848:9848"]

volumes:
  mysql-data:
```

## 4. Nacos 配置模板（robot-system.yml，group=ROBOT_CLOUD）

```yaml
spring:
  datasource:
    url: jdbc:mysql://prod-mysql:3306/robot_cloud?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true
    username: ${ROBOT_MYSQL_USER}
    password: ${ROBOT_MYSQL_PASS}
  data:
    redis:
      host: prod-redis
      password: ${ROBOT_REDIS_PASS}
```

Sentinel 网关流控规则（dataId `robot-gateway-flow-rules`，group `SENTINEL_GATEWAY_GROUP`）：

```json
[
  { "resource": "robot-auth",  "grade": 1, "count": 200, "intervalSec": 1 },
  { "resource": "robot-system","grade": 1, "count": 500, "intervalSec": 1 }
]
```

## 5. 发布流程

1. `mvn clean install`（测试门禁全绿）
2. 流水线按序发布：`robot-system` → `robot-auth` → `robot-gateway`（被依赖方先上）
3. test 环境：容器启动时 Flyway 自动 migrate（首次 baseline-on-migrate）
4. pre/prod：DDL 预检报告（`reports/DDL_PREVIEW_*`）人工评审 → DBA 窗口执行 → 应用滚动发布（2 实例逐台）
5. 冒烟清单：
   - `POST /auth/login`（admin）→ 2000 内响应，返回 token
   - `GET /auth/menu-list` → 菜单树非空
   - `GET /sys/users?page=1&pageSize=10` → `{list,total}`
   - `GET /actuator/health` → UP

回滚：应用回滚上一镜像；数据库不回滚（expand-only 迁移向后兼容旧应用）。

## 6. 监控与告警

| 维度 | 采集 | 建议告警阈值 |
|---|---|---|
| 存活 | `/actuator/health` | DOWN 1 次 |
| JVM | micrometer prometheus | 堆 > 85% 持续 5m；Full GC > 1 次/10m |
| 连接池 | hikari metrics | active > 90% 持续 1m |
| 网关 | gateway metrics（4xx/5xx/延迟） | 5xx 率 > 1%；P99 > 500ms |
| Redis | Redis exporter | 内存 > 80%；拒绝连接 |
| MySQL | exporter | 连接 > 90%；慢查询 > 10/min |

日志聚合建议接入 ELK/Loki，按 `traceId` 检索；网关与服务的 traceId 已打通。

## 7. 安全基线

- 生产 Redis 必须 rename/disable `FLUSHDB/FLUSHALL/KEYS`；网络仅对应用网段开放
- MySQL 账号最小权限（robot_cloud 库 DML + DDL 分离账号）
- Nacos 控制台开鉴权，命名空间按环境隔离
- `ROBOT_INNER_SECRET` 生产必改默认值（32+ 位随机；泄露等同内网越权面）
- Redis Key 环境段 `ROBOT_ENV` 生产设为 prod，与 dev 共池时隔离（standards/20 §1）
- `doc.html`、`/v3/api-docs` 仅 dev/test 暴露（prod 网关路由屏蔽或 nginx deny）
- 密钥轮换：BCrypt 账号密码 90 天提醒；Redis/MySQL 密码季度轮换
