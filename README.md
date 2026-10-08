# Robot Cloud — Robot 中后台管理系统后端框架

> 基于 **Spring Cloud Alibaba** 的微服务后端框架，为 [Robot_Admin](../Robot_Admin)（Vue3 中后台前端）提供认证、系统管理与权限治理能力。
> 选型与工程规范融合 **Spring Cloud Alibaba 官方最佳实践** 与团队 **wl-skills-bd** 后端规范包（B1~B32 / standards 01~30）的通用要求。

| | |
|---|---|
| 代码基线 | `Java 17` · `Spring Boot 3.2.12` · `Spring Cloud 2023.0.3` · `Spring Cloud Alibaba 2023.0.3.3`（官方版本矩阵） |
| 注册/配置中心 | Nacos（本地 profile 可脱离 Nacos 独立启动） |
| 网关 | Spring Cloud Gateway + Sentinel 网关流控 |
| 认证 | Sa-Token 双 Token（Redis 共享会话，网关/服务统一校验） |
| 持久层 | MyBatis-Plus 3.5.12 + MySQL 8 + Flyway（唯一迁移写入者） |
| 缓存/锁 | Redis（TTL 强制 + 抖动）+ Redisson（唯一锁实现） |
| 服务间调用 | OpenFeign + LoadBalancer（降级工厂 + 显式超时） |
| 文档 | OpenAPI 3（springdoc 2.3）+ Knife4j |
| 构建 | Maven 多模块（14 模块）· 全量构建 `mvn clean install` 已验证全绿 |

---

## 1. 快速开始

### 1.1 环境要求

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | 17+（本机验证：Zulu 21） | 编译目标 17 |
| Maven | 3.8+（本机验证：3.9.16） | |
| MySQL | 8.0+ | 字符集 utf8mb4；库名 `robot_cloud` |
| Redis | 6.2+ / 7.x | 会话 + 缓存 + 锁 |
| Nacos | 2.2.x+（可选） | 本地默认关闭，生产/dev 必启 |

### 1.2 初始化数据库

```bash
mysql -uroot -p -e "CREATE DATABASE robot_cloud DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"
```

服务启动时 Flyway 自动执行 `robot-system/src/main/resources/db/migration/` 下 `V1__init_schema.sql`、`V2__seed_data.sql`（禁止手工执行 DDL —— Flyway 是 schema 唯一迁移写入者）。

### 1.3 构建与启动

```bash
# 全量构建（含单元测试，已闭环验证）
mvn clean install

# 启动顺序：MySQL/Redis → robot-system → robot-auth → robot-gateway
java -jar robot-services/robot-system/target/robot-system-1.0.0-SNAPSHOT.jar
java -jar robot-services/robot-auth/target/robot-auth-1.0.0-SNAPSHOT.jar
java -jar robot-gateway/target/robot-gateway-1.0.0-SNAPSHOT.jar
```

环境变量（默认值适用于本地）：

| 变量 | 用途 | 默认 |
|---|---|---|
| `MYSQL_HOST/PORT/DATABASE/USERNAME/PASSWORD` | 系统库 | 127.0.0.1:3306/robot_cloud/root |
| `REDIS_HOST/PORT/PASSWORD/DATABASE` | 会话/缓存/锁 | 127.0.0.1:6379 |
| `NACOS_SERVER_ADDR` / `NACOS_NAMESPACE` | 注册配置中心 | 127.0.0.1:8848 |
| `SENTINEL_DASHBOARD` | 流控控制台 | 127.0.0.1:8858 |
| `ROBOT_ENV` | Redis Key 环境段（多环境共池防互踩） | local |
| `ROBOT_INNER_SECRET` | /inner/** 服务间鉴权密钥（生产必须覆盖） | robot-inner-dev-secret |
| `ROBOT_CORS_ORIGINS` | 网关 CORS 白名单 | http://localhost:* |

### 1.4 默认账号与验证

| 项 | 值 |
|---|---|
| 账号 | `admin` / `admin123`（BCrypt 摘要随种子数据写入，首登后必须改密） |
| 登录接口 | `POST http://localhost:8080/auth/login` `{"username":"admin","password":"admin123"}` |

验证链路：

```bash
# 1. 登录（admin 只有一个公司上下文 → 直接返回会话）
curl -X POST localhost:8080/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}'

# 2. 用返回的 token 拉取动态菜单
curl localhost:8080/auth/menu-list -H "Authorization: Bearer <token>"

# 3. 系统管理（分页契约：page/pageSize → {list,total,page,pageSize}）
curl "localhost:8080/sys/users?page=1&pageSize=10" -H "Authorization: Bearer <token>"
```

### 1.5 前端对接

Robot_Admin `.env.development` 设置 `VITE_API_BASE = http://localhost:8080`、`VITE_AUTH_MODE = remote` 即可联调。接口契约逐条对齐前端 `src/api/auth.contract.ts` 与 `sys-manage/*/data.ts`（详见 [docs/api-contract.md](docs/api-contract.md)）。

---

## 2. 工程结构

```text
Robot_Cloud/
├── robot-dependencies/              # 版本治理 BOM：第三方版本唯一登记处（B27）
├── robot-gateway/                   # API 网关（8080）：路由/鉴权/TraceId/限流
├── robot-common/                    # 公共组件（自动装配）
│   ├── robot-common-core/           # Result / ResultCode / ServiceAssert / PageQuery / PageResult
│   ├── robot-common-web/            # 全局异常 / TraceId 过滤器 / Jackson 约定
│   ├── robot-common-security/       # Sa-Token 装配 / AuthUtil / LoginUser / 401·403 映射
│   ├── robot-common-redis/          # RedisHelper（TTL 强制+抖动）/ LockTemplate（Redisson）/ Key 规范
│   └── robot-common-mybatis/        # MP 插件链 / 租户拦截 / 审计填充 / BaseEntity / ID 生成器
├── robot-api/
│   └── robot-api-system/            # 跨服务契约：/inner/** Feign 客户端 + DTO + 降级工厂
├── robot-services/
│   ├── robot-auth/                  # 认证服务（9201）：AuthService 编排 + AuthSessionManager 会话生命周期
│   └── robot-system/                # 系统服务（9202）：用户/角色/菜单(读/写分离)/部门/字典/权限治理
└── docs/                            # 架构与工程文档（见下）
```

服务内包结构遵循 **业务子域优先**（wl-skills-bd standards/02）：

```text
com.robot.system
├── api/                        # 领域模型层（entity / dto / vo / query 按子域分包）
├── user/  role/  menu/  dept/  dict/  permission/
│   ├── controller/             # REST（含 controller/inner 内部接口）
│   ├── service/                # 直接 Service 类（默认不建接口，多实现才拆 port/impl）
│   └── mapper/                 # MyBatis 接口
└── config/                     # 服务级配置
```

## 3. 文档索引

| 文档 | 内容 |
|---|---|
| [docs/architecture.md](docs/architecture.md) | **架构设计（细致）**：全景图、请求链路、认证流程、多租户、数据流、部署拓扑 |
| [docs/tech-selection.md](docs/tech-selection.md) | **选型论证**：版本矩阵、MySQL/Redis/Sa-Token/MP 等逐项对比与结论 |
| [docs/api-contract.md](docs/api-contract.md) | 前后端接口契约：路径/方法/参数/响应与 Robot_Admin 逐条对齐 |
| [docs/database-design.md](docs/database-design.md) | 数据库设计：表字典、八列治理基线、索引与软删策略 |
| [docs/deployment.md](docs/deployment.md) | 部署与运维：环境矩阵、Nacos 配置、docker-compose、监控 |
| [docs/conventions-bd-mapping.md](docs/conventions-bd-mapping.md) | **wl-skills-bd 规范映射**：采纳清单 + 显式偏差登记（含理由） |

## 4. 核心设计决策（速览）

1. **响应契约以前端为准**：成功码 `0`（Robot_Admin `BUSINESS_SUCCESS_CODES=[200,0,'200','0']` 且 activate 强校验 `'0'`），响应同时携带 `message` 与 `msg`；分页 `{list,total,page,pageSize}`。与 jh4j `2000/records` profile 的差异是**有意决策**，登记于 conventions-bd-mapping.md。
2. **多租户 = 统一拦截器**：MyBatis-Plus `TenantLineInnerInterceptor` + `AuthUtil.getLoginCompanyId()`，未登录时租户条件恒假（宁查空不泄露）；全局表白名单显式登记。
3. **软删语义**：`DELETE_FLAG` 1=有效 / 0=删除（jh4j profile 语义），MP 逻辑删除全局启用，物理删/TRUNCATE 禁止。
4. **Redis 写入强制 TTL**：`RedisHelper` 不提供无 TTL 写入；TTL 随机抖动防雪崩、空值占位防穿透 + 互斥重建防击穿；分布式锁唯一走 Redisson `RLock`（watchdog 续期）；通用 Key 构建器在 common，业务键（AuthKeys/SystemKeys）归属各服务。
5. **框架零业务耦合**：common 模块不含任何 sys_* 表默认值/业务常量/业务键——全局表白名单由服务 yml 声明、业务常量归属 robot-system、业务键归属各服务；实体基类拆分 `BaseEntity`（全局表）/`TenantEntity`（租户表）。
6. **双 Token 轮换**：访问令牌 30 分钟（Sa-Token 会话）；刷新令牌 7 天、一次性、旧换新即作废；登录票据 5 分钟一次性，多公司选择后激活。
7. **内部接口 `/inner/**`**：仅供服务间 Feign，网关层屏蔽外部访问并清洗伪造头。
8. **审计留痕**：权限变更（角色/菜单/临时授权）追加写入 `sys_permission_audit_log`，只增不改。

## 5. 构建验证状态

| 验证项 | 结果 |
|---|---|
| `mvn clean install`（14 模块） | ✅ BUILD SUCCESS |
| 单元测试 | ✅ 55 个用例（含实体↔DDL 结构守护 + 变异验证、异常映射契约、白名单双侧回归、权限变更记录器降级语义），0 失败 0 错误（契约/安全/租户/ID/数据范围/网关过滤器/内部鉴权/装配冒烟） |
| 上下文装配冒烟（H2 + Mock Redisson） | ✅ gateway、robot-auth、robot-system 自动装配链完整 |
| 实体↔DDL 结构守护 | ✅ 16 实体全量对齐（映射列/租户基类双向断言），已做变异验证（错误基类/幽灵字段均被捕获） |
| BCrypt 种子密码可验证 | ✅ `admin123` 与种子哈希匹配（测试固定） |

> 说明：本机无 Docker/MySQL/Redis 实例，运行时链路（登录全流程、Flyway 对真实 MySQL 执行）按 [docs/deployment.md](docs/deployment.md) 步骤在具备基础设施的环境执行；编译期与装配期已闭环验证。

## 6. License

MIT（与 Robot_Admin 前端保持一致）。
