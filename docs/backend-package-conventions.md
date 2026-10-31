# 后端包组织约定

采用按业务聚合、业务内按职责分层的组织方式。先通过目录找到业务，再沿 Controller、Service、Mapper 阅读用例；请求、返回视图和持久化模型归属明确的业务。目录按实际文件建立，不预建空层，不要求每个 Service 都有接口和实现类。

## 单个服务内的业务范例

`robot-system` 的 `com.robot.system.user` 是完整范例：

```text
user/
├── controller/      外部 HTTP 与 inner 内部 HTTP 入口
├── service/         用例编排、权限和归属复验、事务
│   └── context/     登录上下文装配协作者
├── dto/             用户查询与写入请求
├── vo/              列表、详情的输出视图
├── entity/          平台账号、公司成员及成员角色绑定模型
└── mapper/          上述模型的数据访问
```

| 位置 | 职责与约束 |
| --- | --- |
| `controller` | 参数绑定、Bean Validation、调用 Service 和返回响应；不直接操作 Mapper、数据库或 Entity |
| `service` | 编排业务用例并确定事务边界；查询和写命令按真实复杂度拆分，保留相关写入的原子性 |
| `dto` | 明确请求白名单和查询条件；不映射数据库，不依赖 Service 或 Mapper |
| `vo` | 定义客户端输出；不直接返回 Entity，敏感数据库字段不因模型复用进入响应 |
| `entity` | MyBatis 数据映射及持久化状态；不依赖 Controller、Service 或 Mapper |
| `mapper` | 实体与查询投影的数据访问；XML 的 namespace、类型引用和语句 ID 与接口对应 |

数据模型按数据所有权归并。例如用户业务持有平台账号、公司成员及成员角色关系；角色定义仍属于角色业务。跨业务暂有的实体或 Mapper 访问是现有耦合，移动包不能自动消除它。逐步收敛时，应提取明确的业务查询或命令接口，并同时验证权限和事务，避免仅用一层无职责的转发包装掩盖依赖。

复杂业务可以按实际职责增加 `policy`、`support`、`port`、`outbox` 等子包。只有存在多实现、外部系统适配或明确跨模块边界时才抽象接口；小业务直接使用具体 Service。类型名继续使用可读的业务含义，如 `UserSaveRequest`、`UserVO`，不为目录一致性额外改名。

## 共享模块与跨服务契约

本地业务 DTO、VO、Entity 与独立 Maven 契约模块承担不同职责：

- `robot-api-system` 保存跨服务 DTO、Feign 及必要的客户端配置，不依赖 System 实现。
- 其他服务通过契约调用 System，不导入本地 `user.entity` 或实现 Service。
- 已采用独立 `*-entity`、`*-api` 模块的项目，继续让实现模块依赖契约/共享模型模块；禁止将共享类型搬入实现模块后产生反向依赖。
- 在共享模型模块中按相同业务名称归类。例如 `api/dto/planning`、`api/vo/planning`、`api/entity/planning` 与实现模块的 `planning/controller`、`planning/service`、`planning/mapper` 对应；既有公开全限定类名需要兼容评估，不能只为目录外观整体改名。

统一的是业务归属、职责命名和依赖方向。单模块可以就近聚合六类目录，多模块通过一致的业务名称建立对应关系。

## 迁移与持续约束

移动 Java 包时，同步检查 import、反射类名、MyBatis 别名扫描、XML 类型引用、序列化类型信息、机器契约及精确源码质量复核。Mapper 包和接口未变化时保持原 namespace；HTTP URL、JSON 字段、数据库表列、迁移校验和、权限和事务语义独立验证。

测试按业务或跨业务用例归属组织。现有 ArchUnit 规则保护 Controller 与持久化边界，用户 DTO、Entity 不能反向依赖执行层；真实 MyBatis 容器测试验证用户模型与尚在原位置的实体均可解析。实体与 DDL 对账从 System 业务根包发现 `@TableName`，保留完整数量、列映射及租户归属断言，避免搬包后漏扫。

维护时从 `user/controller/UserController` 进入，经 `UserService`、`UserQueryService` 和写入协作者到 `user/mapper`；参数看 `user/dto`，返回字段看 `user/vo`，表映射看 `user/entity`。其他尚在 `system/api` 的业务模型按独立任务逐步归并。

参考：[Spring Boot 代码组织](https://docs.spring.io/spring-boot/reference/using/structuring-your-code.html)、[Spring Modulith 模块边界](https://docs.spring.io/spring-modulith/reference/fundamentals.html)。本约定采用业务聚合思想，当前不增加新的运行框架。
