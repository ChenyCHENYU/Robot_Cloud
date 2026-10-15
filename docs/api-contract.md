# 当前后端接口契约与 Admin 配套要求

以当前 Controller、DTO、VO 与服务规则为准；Robot_Admin需同步表单版本字段和产品语义后再做联调验收。接口路径一致不代表已完成前后端全链路验证。

## 通用协议

| 项 | 当前合同 |
| --- | --- |
| 入口与身份 | 网关8080；Authorization: Bearer token；不接受客户端公司字段扩大当前身份范围 |
| Result | code=0成功；message/msg同值；data和traceId为null时可省略，traceId主要在异常响应返回 |
| 分页 | GET page/pageSize，默认1/10；接口上限200；data为list/total/page/pageSize |
| JSON类型 | ID为不透明String；total、页码、expiresIn等计数/期限为JSON number；时间为UTC yyyy-MM-dd HH:mm:ss，无时区偏移；非法日历日期拒绝，客户端按UTC解析/编码 |
| 失败判定 | 同时检查HTTP与Result.code；Sa未登录/无权限及内部信任拒绝是HTTP401/403，网关拒绝/限流用401/403/429；普通ServiceException和参数校验通过Result返回，不能假定所有业务失败都改HTTP状态 |
| 写入 | DTO白名单、@Valid；服务器生成id/创建审计/软删/初始revision。未知字段忽略不等于可以写Entity治理字段 |
| TraceId | X-Trace-Id仅保留1–64位字母/数字/下划线/短横线，非法或空值换为32位hex；网关与服务响应头回传，异常Result附traceId |

## 认证 `/auth/**`

| 方法与路径 | 请求 | 响应/状态语义 |
| --- | --- | --- |
| POST /auth/login | username、password、可选contextId | 单上下文或合法预选直接会话；多上下文未选择返回一次性loginTicket |
| POST /auth/login-companies | username、password | 凭据验证通过后供给 data.companies：id/isPrimary/tenantName/companyName；缺密拒绝，未知账号/错密使用同登录失败合同 |
| POST /auth/context/activate | loginTicket、contextId | 重新验证账号/上下文后原子消费票据并返回完整会话；RPC临时失败不先消费票据 |
| POST /auth/context/switch | contextId，需有效Bearer | 复验当前族及目标成员，再轮换当前设备access/refresh；其他设备不切公司 |
| POST /auth/refresh-token | refreshToken | CAS提交新族状态；返回RefreshTokenResponse，仅token/refreshToken/expiresIn，旧凭据失效；重复消费拒绝 |
| POST /auth/logout | Bearer，无新增请求体 | 可用自然过期access摘要索引注销族；无token拒绝，已注销幂等；旧轮换索引清理后不再定位后继族 |
| GET /auth/menu-list | Bearer | RemoteMenuRouteDTO树：path/name/component/redirect/meta/children；仅有效授权及完整导航祖先 |
| GET /auth/button-list | Bearer | 路径→精确权限码数组；页面、按钮与导航祖先不互相附赠权限 |
| GET /auth/data-permission | Bearer | module/scope数组；仅显示范围不能证明每个业务查询已经执行裁剪 |

直接会话示例：

```json
{
  "code": 0,
  "data": {
    "token": "opaque-access",
    "refreshToken": "opaque-refresh",
    "expiresIn": 1800,
    "activeContext": {
      "id": "context-id",
      "isPrimary": true,
      "tenantId": "tenant-id",
      "tenantName": "Tenant",
      "companyId": "company-id",
      "companyName": "Company",
      "roles": [
        {
          "id": "role-id",
          "name": "Role"
        }
      ]
    },
    "availableContexts": [
      {
        "id": "context-id",
        "isPrimary": true,
        "tenantId": "tenant-id",
        "tenantName": "Tenant",
        "companyId": "company-id",
        "companyName": "Company",
        "roles": [
          {
            "id": "role-id",
            "name": "Role"
          }
        ]
      }
    ],
    "user": {
      "id": "user-id",
      "username": "user",
      "displayName": "User"
    }
  }
}
```

待激活形态为token/refreshToken空串、expiresIn=0、loginTicket及availableContexts；发现阶段不返回角色。`expiresIn`必须是数字。公司身份存在token Session中，客户端不得解释family、拼接公司头或复用另一个设备的context。

账号与成员安全版本改变后旧access、ticket、refresh被拒绝；停用后再启用不能复活。旧Session/refresh/ticket格式不兼容，本轮部署要求受控切换并重新登录。CAS故障补偿与Redis HA限制见 [auth-session-lifecycle.md](auth-session-lifecycle.md)。

## 系统资源 `/sys/**`

| 方法 | 路径 | 权限码 | 当前用途 |
| --- | --- | --- | --- |
| GET | /sys/users?page&pageSize&keyword&status&deptId | sys:user:list | 当前公司成员分页，keyword匹配账号/昵称/邮箱；附加User读取范围 |
| GET | /sys/users/{id} | sys:user:query | 同一读取范围的详情，无passwordHash |
| POST / PUT / DELETE | /sys/users、/sys/users/{id} | sys:user:save / update / delete | 新建账号并入公司；编辑当前成员；移出本公司并撤销本公司授权，保留平台账号/其他公司 |
| POST | /sys/users/{id}/reset-password | sys:user:resetPassword | {password,expectedRevision,expectedMembershipRevision}；账号/成员双版本，当前公司独占账号与授权上界校验 |
| PUT | /sys/users/{id}/status | sys:user:update | {status:0/1,expectedRevision}修改当前成员；版本来自membershipRevision，保留内置账号、自身停用限制 |
| GET | /sys/departments | sys:user:list | 当前公司部门树 |
| GET | /sys/roles?page&pageSize&keyword&status | sys:role:list | 当前公司角色分页 |
| GET | /sys/roles/options | 登录即可 | 可用角色选项id/name/code |
| GET | /sys/roles/{id}、/{id}/users、/{id}/data-scopes、/{id}/temp-authorizations | sys:role:query | 详情、当前公司成员、范围与该角色临时授权 |
| POST / PUT / DELETE | /sys/roles、/sys/roles/{id} | sys:role:save / update / delete | 当前公司角色写入；有效资源、授权上界、自授与引用检查 |
| PUT | /sys/roles/{id}/permissions | sys:role:update | {revision,permissionIds}，菜单/按钮实体ID分别绑定 |
| GET | /sys/menus | sys:menu:list | 管理树含permission/permCode/status/hidden/visible/keepAlive/remark/revision |
| POST / PUT | /sys/menus、/sys/menus/{id} | sys:menu:save / update | JSON或FormData；name/type/permission/sort及旧名称兼容 |
| PUT | /sys/menus/{id}/move | sys:menu:update | {targetId,position,revision}；inside/inner/before/after，必需源版本，树锁内校验存在、环、深度与稳定排序 |
| DELETE | /sys/menus/{id} | sys:menu:delete | query.expectedRevision 必填；有子菜单返回40402，仍有按钮返回40903 |
| GET / POST | /sys/menus/{menuId}/buttons | sys:menu:list / save | 有效页面的按钮清单/新增；body.menuId存在时必须等于路径 |
| POST | /sys/menus/buttons | sys:menu:save | 保留旧按钮新增路径，body.menuId必需 |
| GET / POST；PUT / DELETE | /sys/menu-buttons；/sys/menu-buttons/{id} | sys:menu:list / save；save / delete | 列表/新增及更新/删除；删除 query.expectedRevision 必填；不提供GET详情 |
| GET / POST / PUT / DELETE | /sys/dictionaries、/sys/dictionaries/{id} | sys:dict:list / save / save / delete | 全局类型/项树及写入；删除 query.expectedRevision 必填；关联锁/重复值规则见字典契约 |
| PUT | /sys/dictionaries/{id}/status | sys:dict:save | {status:0/1,revision}，可切换类型或项 |
| GET | /sys/dictionaries/by-code?code= | 登录即可 | 启用类型下启用项的label/value；未知或停用类型为空数组 |
| GET | /sys/permissions、/sys/permissions/tree | sys:permission:list | 绑定资源目录；id是真实菜单/按钮ID，permCode不是绑定ID |
| GET | /sys/data-permissions、/sys/permission-constraints、/sys/permission-audit-logs | sys:permission:list | 当前公司范围/只读约束/最近500条审计 |
| GET / POST | /sys/temp-authorizations | sys:permission:list / tempAuthorize | 当前公司临时角色授权；POST userId/roleId/effectFrom/effectTo/reason |
| GET | /sys/temp-authorizations/page、/sys/permission-audit-logs/page | sys:permission:list | page/pageSize 与过滤字段，返回 list/total/page/pageSize，不以截断条数代替总数 |
| PUT | /sys/temp-authorizations/{id}/revoke | sys:permission:tempAuthorize | {expectedRevision}；公司范围与永久授予上界复验，审计/epoch/Outbox 同事务 |
| GET | /sys/governance-capabilities | sys:permission:list | 明确数据范围/约束/审计只读，字段模型 read/write=false，临时角色可写/可撤回 |

表中的简写权限如`update`完整前缀为所在行的`sys:user:`或`sys:role:`。临时授权按人授予已有角色，半开时间窗且最长30天，明确起止时间和原因。菜单/按钮/字典全部写入同时校验永久平台管理员事实；公司 SUPER_ADMIN、临时角色或单个页面权限不能替代平台身份。已绑定资源不能借普通 CRUD 改写权限码。

## 编辑版本与字段语义

Admin需保留详情/列表返回的版本，不能在snapshotDraft或提交映射中丢弃。旧客户端缺少必填字段会拒绝，后端不能从当前库读取版本替它代填。

| 写命令 | 客户端版本 | 关联/兼容语义 |
| --- | --- | --- |
| PUT角色及单独permissions | revision | permissions未提交保留，[]清空；permissionIds与menuIds/buttonIds不能同时提交 |
| PUT成员完整表单 | membershipRevision；变更全局资料/密码另需revision | roleIds未提交保留、[]清空；username不可修改，password空表示不改；共享账号资料/密码走本人入口 |
| PUT成员status；DELETE成员 | expectedRevision = membershipRevision | DELETE放query中；缺失、负值或旧版本拒绝，不能由服务端代填 |
| POST用户reset-password | expectedRevision = revision；expectedMembershipRevision = membershipRevision | 专用DTO；先校验成员资格与授权，再在同一事务比较并递增两类版本；成员状态不改变 |
| DELETE角色 | expectedRevision = 角色revision | query必填；角色、关联清理、审计、epoch与outbox同事务 |
| PUT菜单/按钮 | revision | menu类型与button所属页面不能修改；现代字段与legacy alias兼容，但治理字段不可写 |
| PUT字典类型/项及status | revision | 类型code、项code/label/value独立；兼容字段同时提交必须一致 |
| PUT菜单move | revision 必填 | 旧版本冲突，移动至根显式 parent_id=NULL；绝对路径规范化后按 Vue Router 默认大小写不敏感语义判重 |
| DELETE菜单/按钮/字典 | query.expectedRevision | 对读取到的实体版本执行 CAS，禁止服务端补填最新版本 |
| PUT临时授权revoke | expectedRevision | 只撤回当前公司指定记录，过时快照不覆盖新状态 |

缺版本返回code400，陈旧或实际更新0行返回40902；关联写和审计随同事务回滚。新增忽略伪造revision并初始化为0。资源批量上限500，拒绝空ID、重复/失效/跨公司ID；空集合按相应字段的清空语义处理。

菜单现代字段：name/type/parentId/path/component/redirect/permission/icon/sort/status/hidden/keepAlive/remark/revision。type为directory/menu，hidden为0/1，keepAlive为boolean；旧menuName/menuType/permCode/sortNo/statusFlag与visibleFlag/keepAliveFlag兼容，冲突标志拒绝。按钮permission必须精确，不能包含任意通配符。

资料字段 Patch：省略 nickname/email/phone 表示保留，明确 null 表示清空；邮箱/电话空串归一为清空。本人昵称显式空白拒绝。成员 departmentId/deptId 来自当前公司成员，省略保留、null 清空；roleIds 省略保留、[] 清空。JSON 的 presence 标志是服务端解析状态，不接受客户端伪造。平台账号的全局资料/密码只在公司独占时由成员管理接口修改，共享账号通过本人受控入口；平台标志不会隐含一个尚未提供的全局密码重置接口。

用户读取已执行self/department/department_below/all范围，all仍限制当前公司成员；非法部门或祖先链退回本人，无有效角色/只有未实现custom时拒绝。新配置custom被拒绝，通用字段可见/编辑/脱敏和约束执行器尚未实现。详见 [system-permission-contract.md](system-permission-contract.md)。

## 缓存通知运维契约 `/sys/cache-outbox`

全部端点要求 `sys:cache-outbox:manage`，Service 再验证操作者具有独立平台管理员事实，且在当前公司具有**永久**超管角色。临时超管或普通角色不能借通配符越过此约束。V10 只登记权限按钮，挂在已有菜单管理页面；不自动向普通角色授权，不意味着 Admin 已实现对应页面。

| 方法与路径 | 请求/响应 | 行为 |
| --- | --- | --- |
| GET /summary | pending/due/failed/retries/maxAttempts/oldestAgeSeconds/quarantined | 只读积压摘要，隔离事件仍是未解决 pending |
| GET /events?limit= | limit 1–100；事件摘要数组 | 不返回缓存 key、凭据、SQL、异常原文或堆栈 |
| GET /{eventId}/replays | 最近重放审计 | actorUserId/actorCompanyId、受控原因和版本前后值 |
| POST /{eventId}/replay-preview | {reason} → event/reason/planHash | reason 仅 DEPENDENCY_RECOVERED 或 OPERATOR_RETRY；查询当前状态后预览 |
| POST /{eventId}/replay | {reason,planHash} → auditId/eventId/revision | 客户端明确确认预览后提交；行锁内重新比对操作者、原因和事件状态；旧预览拒绝 |
| POST /archive-preview | {retentionDays,limit} → eventIds/planHash | 保留30–3650天，单批1–500；只预览达到保留期的已完成历史 |
| POST /archive | {retentionDays,limit,planHash} → 归档数 | 在锁内重验当前候选、操作者与版本，逻辑标记 archived_at；拒绝 pending/隔离/新重放或漂移快照 |

重放只调整已有通知的投递计划，不重新执行用户/角色业务、不回退 epoch、不重置历史 attempts。投递围栏版本与追加审计同事务，审计失败不改变事件；调度器提交后继续投递。两个客户端拿相同预览并发提交时只能成功一个，其余返回版本冲突。已完成通知也可重新安排，其历史事实不因此删除。

## 机器契约与变更规则

`contracts/system/` 与附加正式合同测试覆盖 System 所有 Controller 的路径、权限、DTO/VO 字段和校验；测试直接对照 Spring HandlerMapping、Jackson 和 Bean Validation。`contracts/database/full-schema.json` 固定20表全部结构属性与12个迁移 SHA，真实 MySQL 新建及 V10 升级逐项对账，不从当前实体自动刷新期望。JSON、生产实现与测试一起修改并审查；接口变动需确认配套 Admin 的受控切换。

版本请求示例：

```http
PUT /sys/users/user-id/status
Content-Type: application/json

{"status":0,"expectedRevision":7}
```

```http
DELETE /sys/roles/role-id?expectedRevision=3
```

```json
{"password":"client-entered-new-password","expectedRevision":2,"expectedMembershipRevision":7}
```

第三个示例是管理员重置密码请求，字段禁止记录日志；成功后重新读取账号和成员两类版本。400/40902 都需停止当前提交；版本冲突应重新读取并让用户判断，不静默以新版本重试。

## 本人账户与内部契约

`/account/**`走system，只操作当前账号；GET profile、login-records（当前公司最近20条）、security-settings、activity-logs（当前公司最近20条），PUT profile {nickname,email,phone}，PUT password {oldPassword,newPassword}。安全设置展示不代表双因素认证已经实现。

| 方法 | 内部路径 | 身份边界 |
| --- | --- | --- |
| GET | /inner/user/by-username?username=、/inner/user/{userId}、/inner/user/{userId}/contexts | 可信服务账号供给；Feign不混入现有用户委托；返回凭据摘要仅供认证内部契约 |
| GET | /inner/menu/{companyId}/routes?userId=、/inner/menu/{companyId}/button-list?userId= | 完整委托与有效成员；路径/查询用户必须对齐 |
| GET | /inner/permission/{companyId}/data-scope?userId= | 同一委托与成员边界 |
| POST | /inner/log/login-record | 服务身份、可选委托body对齐；身份拒绝不被日志尽力而为catch吞掉 |

网关清洗全部X-Inner-*并阻断内部路径；提供方仍验证共享secret和委托。Feign仅限定RemoteSystemService的内部受众，不提供按服务独立授权/mTLS保证。

## 错误码与联调验收

| code | 语义 |
| --- | --- |
| 0 | 成功 |
| 400/401/403/404/429/500 | 参数/未登录/无权限/不存在/限流/繁忙；与HTTP的差异见通用协议 |
| 40101–40106 | 登录失败/停用/票据无效/上下文拒绝/refresh无效/无公司 |
| 40201–40203 | 用户不存在/账号重复/密码不合规则 |
| 40301–40302 | 角色不存在/编码重复 |
| 40401–40402、40501–40502、40601–40602 | 菜单、部门、字典不存在或引用/重复限制 |
| 40901–40903 | 数据重复/版本冲突/引用禁删 |

联调至少验收：双设备公司隔离、刷新竞争/注销、停用再启用旧令牌拒绝、精确按钮不给页面、用户范围SQL效果、编辑版本冲突、菜单move及按钮路径、独立字典code/value。不得只以接口200或菜单非空判断成功。真实 Admin 页面联调须单独验收；基础设施验证范围见 [部署文档](deployment.md)，不能将后端隔离环境结果推导为生产或页面验收通过。
