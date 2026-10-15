<div align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/brand/lockup-dark.svg">
    <img src="docs/assets/brand/lockup-light.svg" width="720" alt="Robot Cloud — Spring Cloud Alibaba 后端框架">
  </picture>

  <p><strong>面向 Robot Admin 的 Spring Cloud Alibaba 后端框架</strong></p>
  <p>认证 · 公司上下文 · 系统管理 · 权限治理</p>

  <p>
    <a href="robot-dependencies/pom.xml"><img src="https://img.shields.io/badge/Java-17-2563EB?style=flat-square&amp;labelColor=172B43" alt="Java 17 编译目标"></a>
    <a href="robot-dependencies/pom.xml"><img src="https://img.shields.io/badge/Spring_Boot-4.1.1-0E9F8C?style=flat-square&amp;labelColor=172B43" alt="Spring Boot 4.1.1"></a>
    <a href="robot-dependencies/pom.xml"><img src="https://img.shields.io/badge/Cloud_Alibaba-2025.1.0.0-2563EB?style=flat-square&amp;labelColor=172B43" alt="Spring Cloud Alibaba 2025.1.0.0"></a>
    <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-64748B?style=flat-square&amp;labelColor=172B43" alt="MIT License"></a>
  </p>

  <p>
    <a href="#本地构建与启动">快速开始</a> ·
    <a href="docs/architecture.md">架构与演进</a> ·
    <a href="docs/api-contract.md">API 契约</a> ·
    <a href="docs/deployment.md">部署与运维</a> ·
    <a href="https://github.com/ChenyCHENYU/Robot_Admin">Robot Admin</a>
  </p>
</div>

---

## 项目定位

Robot Cloud 为 Robot Admin 提供认证、公司成员、组织与权限管理能力。当前采用 Gateway、Auth、System 三个运行服务，配合统一的公共组件、API 契约和依赖 BOM；System 内保留相关业务的本地事务。

- Maven 坐标：`com.robot:robot-cloud:1.0.0-SNAPSHOT`
- 运行服务：`robot-gateway`、`robot-auth`、`robot-system`
- 工程组织：独立服务、五个公共组件、跨服务 API、统一依赖 BOM。
- 演进方向：先巩固内部业务边界和持续验证，再按数据所有权、容量及独立部署需求拆分。

[架构与演进](docs/architecture.md)描述服务边界与拆分路线；[API 契约](docs/api-contract.md)说明 Robot Admin 的请求字段、权限和兼容要求。

## 目录

- [能力与边界](#能力与边界)
- [服务拓扑](#服务拓扑)
- [工程结构](#工程结构)
- [技术基线](#技术基线)
- [本地构建与启动](#本地构建与启动)
- [配置约定](#配置约定)
- [Robot Admin 配对](#robot-admin-配对)
- [持续验证与数据库升级](#持续验证与数据库升级)
- [缓存通知与运维](#缓存通知与运维)
- [开发约定](#开发约定)
- [分支与协作](#分支与协作)
- [文档导航](#文档导航)

## 能力与边界

| 能力 | 当前行为 |
| --- | --- |
| 认证 | 密码登录、多公司激活、设备独立上下文、刷新令牌轮换与注销 |
| 撤销 | 账号/成员安全版本核验；改密、停用或移出后旧凭据失效 |
| 成员 | 平台账号和公司成员分开维护；每个公司成员一个主部门，查询区分账号/成员状态 |
| 授权 | 有效永久/临时角色；页面与按钮精确绑定；管理授权受永久权限上界约束 |
| 数据范围 | 用户列表和详情执行本人、部门、下级部门及当前公司全量范围 |
| 系统资源 | 全局菜单/按钮/字典写入另需永久平台管理员事实；公司超管和临时角色不能替代 |
| 并发控制 | 编辑、删除、移动、撤回提交读取到的版本；关联写与删除在公司协调锁内重新核验 |
| 缓存一致性 | 数据库 epoch 为权威；业务、审计、epoch 与 Outbox 同事务，Redis 通知可重试 |
| 验证与观测 | 行为/架构测试、机器契约、迁移/制品检查、升级验收、Prometheus 与 TraceId |

通用字段权限、复杂工作流、业务消息平台、文件服务和完整 Redis HA 恢复属于后续建设。可选依赖登记或配置文件存在不能单独证明运行能力。

菜单支持根级归属清空和稳定路由名，权限编码受绑定保护；资料支持 Patch 清空，临时授权支持分页和版本冲突处理。治理接口明确返回只读能力；通用约束执行、全局共享账号重置、头像/简介保存暂未开放。

## 服务拓扑

```mermaid
flowchart LR
    Admin["Robot Admin"] --> Gateway["Gateway :8080"]
    Gateway -->|"/auth/**"| Auth["Auth :9201"]
    Gateway -->|"/sys/** /account/**"| System["System :9202"]
    Auth -->|"robot-api-system 内部调用"| System
    Auth --> Redis[("Redis 会话")]
    System --> DB[("MySQL 系统数据")]
    System --> Redis
    Nacos["Nacos 配置与发现"] -. "nacos profile" .-> Gateway
    Nacos -.-> Auth
    Nacos -.-> System
```

网关处理路由、内部身份头清洗与请求跟踪。实际登录态、主体有效性和业务权限在服务侧校验；System 是系统数据属主，Auth 经 API 调用 System。

| 服务 | 默认端口 | 职责 | 网络边界 |
| --- | --- | --- | --- |
| `robot-gateway` | 8080 | API 入口、路由、Bearer 头检查、TraceId、内部路径阻断 | 反向代理提供 TLS |
| `robot-auth` | 9201 | 登录、上下文、刷新族与注销 | 应用内网 |
| `robot-system` | 9202 | 账号/成员、组织、权限、资源、审计和缓存控制 | 应用内网 |

## 工程结构

```text
Robot_Cloud/
├── robot-dependencies/          版本 BOM
├── robot-common/
│   ├── robot-common-core/       Result、分页、错误码、断言、摘要
│   ├── robot-common-web/        MVC、异常、JSON、TraceId
│   ├── robot-common-security/   登录态、族校验、内部信任
│   ├── robot-common-mybatis/    公司隔离、审计、分页、乐观锁
│   └── robot-common-redis/      序列化、缓存、原子操作、锁
├── robot-api/
│   └── robot-api-system/        Feign 与跨服务 DTO
├── robot-gateway/
├── robot-services/
│   ├── robot-auth/
│   └── robot-system/            系统业务与 Flyway 迁移
├── contracts/                   机器接口与数据合同
├── scripts/                     本地和 CI 共用入口
├── .github/workflows/           CI 定义
└── docs/                        架构、接口、部署、运行说明
```

服务之间不依赖实现 jar；common/API 不反向依赖 Auth/System。System 内按业务子域分包，角色查询、角色写命令和临时授权命令分开承担职责。

## 技术基线

版本以 [robot-dependencies/pom.xml](robot-dependencies/pom.xml) 为准。升级须联合验证框架兼容性、行为和制品依赖。

| 项目 | 仓库基线 |
| --- | --- |
| Java / Maven | 真实 Java 17；Maven Wrapper 3.9.16，下载 SHA-256 固定，Enforcer 校验工具链 |
| Spring Boot | 4.1.1；Tomcat 11.0.26 |
| Spring Cloud / Alibaba | 2025.1.3 / 2025.1.0.0 |
| 认证 | Sa-Token 1.46.0 Boot4 starter、Bearer、token Session |
| 持久化 | MyBatis-Plus 3.5.17 Boot4 starter、MySQL、模块化 Flyway starter |
| Redis | Spring Data Redis；Redisson 4.8.0 + 实际传递 `redisson-spring-data-41` |
| 配置/流控 | Nacos、Sentinel，按 profile 启用 |
| 文档/监控 | springdoc 3.1.1、Actuator、Micrometer/Prometheus；生产文档不可访问 |
| JSON | Jackson2 2.21.7 / Jackson3 3.1.7；HTTP/Feign/业务缓存暂用官方 Jackson2 兼容桥，Sa-Token 使用 Jackson3 |

升级同时调整 MVC/WebFlux 测试模块、Gateway/Sentinel 适配器、MyBatis 服务基类、Redis 配置和 Sa-Token 类型白名单。Jackson2 兼容桥是保持现有线路协议的过渡方案，官方已弃用，后续迁移必须覆盖 HTTP/Feign/Redis 类型合同。版本依据与维护边界见 [技术选择](docs/tech-selection.md)。默认验证从实际 CycloneDX SBOM 执行完整 OSV 查询和许可证政策检查，未知许可、漏洞、网络失败和过期豁免均阻断。

## 本地构建与启动

### 环境与构建

准备真实 JDK 17、MySQL 与 Redis；仓库提供 Maven Wrapper。默认 `local` 使用 HTTP 直连，关闭 Nacos/Sentinel；数据库与 Redis 仍是必要依赖。

```bash
# 替换实际 JDK 根目录；macOS 通常以 Contents/Home 结尾
export JAVA_HOME="/path/to/jdk"
export PATH="$JAVA_HOME/bin:$PATH"
java -version
./mvnw -version

# 完整门禁：构建、契约、迁移清单和打包 Mapper 检查
bash scripts/verify.sh
```

### 数据库与配置

新环境创建空库；已有库按升级流程处理：

```sql
CREATE DATABASE robot_cloud
  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
```

在三个进程中注入一致的 Redis、环境段与内部密钥；数据库配置用于 System：

```bash
export SPRING_PROFILES_ACTIVE=local ROBOT_ENV=local DEBUG=false
export MYSQL_HOST=127.0.0.1 MYSQL_PORT=3306 MYSQL_DATABASE=robot_cloud
export MYSQL_USERNAME="<local-user>"
# MYSQL_PASSWORD 从受控本地配置注入，不提交 .env
export REDIS_HOST=127.0.0.1 REDIS_PORT=6379 REDIS_DATABASE=0
# ROBOT_INNER_SECRET 使用三个进程一致的受控密钥
export SPRING_FLYWAY_BASELINE_ON_MIGRATE=false
```

System 从迁移目录应用未执行版本，`baseline-on-migrate` 默认关闭。非空且无 Flyway 历史的库需先评审；不改写已发布迁移，不用 repair 绕过未解释的校验和差异。

### 启动与检查

三个终端按 System → Auth → Gateway 顺序启动：

```bash
java -jar robot-services/robot-system/target/robot-system-1.0.0-SNAPSHOT.jar
java -jar robot-services/robot-auth/target/robot-auth-1.0.0-SNAPSHOT.jar
java -jar robot-gateway/target/robot-gateway-1.0.0-SNAPSHOT.jar
```

```bash
curl -fsS http://127.0.0.1:9202/actuator/health
curl -fsS http://127.0.0.1:9201/actuator/health
curl -fsS http://127.0.0.1:8080/actuator/health
```

随后验证登录、公司激活和受保护接口。`admin/admin123` 仅用于隔离 local 种子。平台管理员须显式启动交接，生产启动会检查开发凭据、账号状态和配置护栏，不能只设置一个“已完成”标志绕过实际凭据检查。交接步骤见 [部署与运维](docs/deployment.md)。

```bash
curl -sS -X POST http://127.0.0.1:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}'

curl -sS 'http://127.0.0.1:8080/sys/users?page=1&pageSize=10' \
  -H 'Authorization: Bearer <access-token>'
```

同时检查 HTTP 状态与 `Result.code`。登录可能直接返回会话，也可能要求使用一次性票据选择公司；health UP 不能替代业务验收。

## 配置约定

| 配置 | 用途 |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | 默认 local；nacos 开启配置与发现 |
| `MYSQL_HOST/PORT/DATABASE/USERNAME/PASSWORD` | System 数据源 |
| `REDIS_HOST/PORT/PASSWORD/DATABASE` | Auth/System 连接；空凭据不发送空 AUTH |
| `SPRING_DATA_REDIS_USERNAME` | 可选 Redis ACL 用户名，按 Spring Boot 标准属性注入 |
| `ROBOT_ENV` | 自定义 Redis key 环境段，需显式设置 |
| `ROBOT_INNER_SECRET` | 内部共享服务密钥，生产覆盖开发默认值 |
| `ROBOT_MANAGEMENT_SECRET` | 生产 Actuator 独立管理密钥，通过 `X-Robot-Management-Secret` 提交 |
| `ROBOT_REDIS_ISOLATED` / `REDIS_SSL` | 生产显式隔离声明、TLS、受控 Redis 凭据；自定义 key 环境段不能隔离 Sa-Token 自身 key |
| `ROBOT_BOOTSTRAP_COMPLETE` | 生产交接完成声明，仍核验真实种子状态 |
| `ROBOT_PLATFORM_ADMIN_BOOTSTRAP/USER_ID/BOOTSTRAP_PASSWORD` | 可信启动交接配置；首次成功后移除交接密码和启用开关 |
| `ROBOT_AUTH_URI/ROBOT_SYSTEM_URI` | local 网关 HTTP 目标 |
| `ROBOT_SYSTEM_URL` | local Auth 的 remoteSystemService Feign 目标 |
| `NACOS_SERVER_ADDR/NACOS_NAMESPACE` | 配置/发现地址与环境 |
| `ROBOT_CORS_ORIGINS` | 前端 origin 白名单 |
| `DEBUG` / `LOGGING_LEVEL_*` | 控制框架日志；不要在认证环境开启请求/响应载荷调试 |

nacos group 为 `ROBOT_CLOUD`，每个服务读取各自 YAML dataId。开发允许 `optional:`；启用 Nacos 配置的生产环境必须覆写为必需导入。生产护栏要求非 local profile、合法独立环境段、至少32字符非开发密钥、独立管理密钥、Redis TLS/凭据/隔离声明、MySQL `sslMode=VERIFY_IDENTITY` 及迁移/运行账号分离。Gateway Actuator 路由管理端点明确关闭，文档路径返回404。配置护栏通过仍需验证证书、网络和实际权限。

详细默认值和受控切换步骤见 [部署与运维](docs/deployment.md)。

## Robot Admin 配对

前端使用远端认证模式，以网关为 API 入口：

- 登录处理直接会话与多公司票据两种结果；合并同设备并行刷新。
- 页面与按钮分别消费精确权限，导航祖先不代表页面授权。
- 编辑保留 `revision`、`membershipRevision`，避免表单映射丢失。
- 用户启停/移出的 `expectedRevision` 来自 `membershipRevision`；角色删除来自角色 `revision`。
- 重置密码提交账号 `expectedRevision` 和 `expectedMembershipRevision`；成功后刷新两类版本。
- 冲突 `40902` 要求刷新并重新确认操作，后端不代填最新版本。
- 用户“删除”表示移出本公司，保留平台账号及其他公司关系。

字段和兼容语义见 [API 契约](docs/api-contract.md)。

配套 Admin 按用户临时授予已有角色，填写起止时间与原因，并支持分页查询和按版本撤回。无授权首页不擅自补权限，动态路由身份稳定；账号状态与成员状态、不同公司主部门分开表示。远端不支持的治理保存、头像/简介或安全配置保持只读，冲突要求重新读取并确认。

资料、安全和活动记录是需要登录的本人基础页面，不依赖业务菜单授权。权限治理导航由角色查询权限控制，页面、接口与按钮分别校验授权。

## 持续验证与数据库升级

```bash
bash scripts/verify.sh

export ROBOT_VERIFY_MYSQL_HOST=127.0.0.1
export ROBOT_VERIFY_MYSQL_PORT="<isolated-mysql-port>"
export ROBOT_VERIFY_MYSQL_USER="<test-user>"
# ROBOT_VERIFY_MYSQL_PASSWORD 从受控环境注入
bash scripts/verify.sh --mysql-upgrade
```

升级门只接受 loopback，创建随机隔离测试库，不允许指定已有库。迁移清单依据真实 SQL 生成版本和哈希；已有版本升级验证数据保全、历史、结构和约束。

| 检查层 | 证明范围 |
| --- | --- |
| Maven / ArchUnit | 行为、事务、序列化与依赖方向 |
| 机器契约 | 端点、权限、输入输出、版本及数据映射 |
| 打包 Mapper | 制品 Mapper 与必要 statement；合法零 XML |
| MySQL 升级 | 隔离已有版本升级、数据保全、校验和及约束 |
| 双 System 故障 | 缓存重试、重复/迟到、重启和版本单调性 |
| 生产验收 | Nacos/Sentinel、Admin、容量、目标数据库版本、HA、备份恢复 |

CI 使用同一入口并归档报告和制品摘要；生产部署按目标环境验证服务发现、容量、备份与恢复。

机器 HTTP 合同覆盖 System Controller 的接口、权限、字段与类型，并配合 MVC 行为负例。数据库新建和历史版本升级均与固定结构基线对账，已发布的 Flyway 迁移保持不可变。Checkstyle、PMD、SpotBugs、Spotless 与 JaCoCo 进入统一门禁：每个业务 Service 类及辅助/内部类至少70%行、60%分支，Controller 至少50%行。缺报告、陈旧报告、分析错误、遗漏类和跳过工具均失败；见 [质量规则](quality/README.md)。

## 缓存通知与运维

`sys_cache_epoch` 为数据库事实源。业务事务内写审计、推进版本并登记 `sys_cache_outbox`；独立调度通知 Redis，失败保留事件并退避重试。

投递可重复，权限通知只单调推进；字典按数据库版本隔离。重放仅重排已有合法事件，经授权、预览哈希、漂移检查和同事务审计，不回退业务或 epoch。

监控 pending/due、最老年龄、尝试次数、隔离事件、失败分类和结果；业务结果指标另计入 HTTP200 中的失败。错误只保留受控分类及关联 ID。毒事件进入隔离，可受控预览/重放；已完成历史可在保留期后预览并逻辑归档，保留审计、版本与漂移核验，不能归档 pending。实际部署的阈值、接收人和演练仍需落地。

## 开发约定

1. 按业务子域组织 Controller、Service、Mapper；查询与命令分清职责。
2. Controller 绑定输入输出；归属、授权、状态及事务在 Service。
3. 远程预取与本地写分开，写事务重新核对依据，外部调用有超时。
4. DTO 为白名单，公司、审计、删除标志及初始版本由服务生成。
5. 账号/成员编辑版本、撤销安全版本和缓存 epoch 分别维护。
6. 业务、授权关联、成功审计和 Outbox 同事务，失败不留下成功事件。
7. 数据库变化追加 Flyway，并同步契约、升级和恢复说明。
8. 跨服务用 API；公共库提取真实复用机制。
9. 改后运行行为与门禁，交付源码/制品哈希及未验证项。

开发工具使用 Node >=22.18.0 和 Bun 1.4.2，服务仍由 Maven 构建和启动。首次 clone 后执行 `bun install --frozen-lockfile`，安装固定版本的 commitlint、Husky 和 `@agile-team/wl-skills-bd@0.37.0`，并启用提交 Hook：

```bash
bun run test:tooling
bun run check:commits
bun run check:bd
git commit -m "feat(system): add company membership commands"
```

提交必须采用 `type(scope): message`，scope 必填，类型白名单与 Robot Admin 相同。本地 Hook 和 CI 共用配置；已共享历史保留，之后的提交范围持续校验。具体类型、安装和检查说明见 [贡献指南](CONTRIBUTING.md)。

BD 用于后端规范与受管资产检查；Robot 项目适配器检查 Sa-Token/public/inner、公司范围、软删、分页及合同。项目使用原生 Java 17 门禁，适用范围和规范差异见 [规范映射](docs/conventions-bd-mapping.md)。

## 分支与协作

| 分支 | 用途 | 合入方向 |
| --- | --- | --- |
| `main` | 稳定主分支，承载经过验证的集成结果 | `dev` 经 `--ff-only` 快进合入 |
| `dev` | 日常集成基线 | 功能、修复和维护分支经 `--ff-only` 快进合入 |
| `feat/<主题>`、`fix/<主题>`、`chore/<主题>` 等 | 从最新 `dev` 创建的短期工作分支，兼容既有 `feature/` | 合入 `dev` 后清理 |

GitHub 为主要协作仓库，Gitee 同步相同的 `main`、`dev` 提交。开始工作时先更新 `dev`，再创建功能分支。任务完成并通过适用验证后提交推送，再依次快进到 `dev`、`main`，同步两个远程并核对 SHA，保持线性历史；交付不只停在功能分支。详细命令、双远程同步和分支保护配置见 [Git 维护流程](docs/git-workflow.md)。

## 文档导航

| 文档 | 内容 |
| --- | --- |
| [架构与演进](docs/architecture.md) | 服务、数据属主、时序、边界、未来拆分 |
| [API 契约](docs/api-contract.md) | 前端协议、版本、权限和错误 |
| [系统权限](docs/system-permission-contract.md) | 角色事实、授予上界、数据范围、临时授权 |
| [认证生命周期](docs/auth-session-lifecycle.md) | 票据、刷新族、CAS、注销与故障 |
| [缓存与字典](docs/cache-and-dictionary-contract.md) | epoch、Outbox、字典与迁移约束 |
| [数据库设计](docs/database-design.md) | 表、字段与关系 |
| [部署与运维](docs/deployment.md) | profile、变量、升级、运行和恢复 |
| [技术选择](docs/tech-selection.md) | 依赖基线和可选扩展 |
| [规范适配](docs/conventions-bd-mapping.md) | 通用规范与 Robot 协议适配 |
| [贡献指南](CONTRIBUTING.md) | 必填 scope、提交 Hook、开发工具与 BD 门禁 |
| [Git 维护流程](docs/git-workflow.md) | main/dev、功能分支、PR 与双远程同步 |
| [品牌资产](docs/assets/brand/README.md) | SVG 标识、字标、配色与使用约定 |

后续优先补运行保障并引入独立业务域。审计读模型、导出或异步任务在形成容量和交付需求后迁出；身份、成员、角色与授权的强一致写暂留 System。拆分条件及阶段见 [架构与演进](docs/architecture.md#演进路线与拆分条件)。
