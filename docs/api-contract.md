# 前后端接口契约（对齐 Robot_Admin）

> 来源：`Robot_Admin/src/api/auth.contract.ts`、`auth.ts`、`permission-manage.ts`、`permission-governance.ts`、`views/sys-manage/*/data.ts`。
> 本文档即后端实现的验收清单；变更必须先改此处并同步前端。

## 0. 全局约定

| 项 | 契约 |
|---|---|
| 网关入口 | `http://{gateway}:8080`（前端 `VITE_API_BASE`） |
| 认证头 | `Authorization: Bearer <token>` |
| 响应包装 | `{ "code": 0, "message": "成功", "msg": "成功", "data": ..., "traceId": "..." }` |
| 成功码 | `0`（前端 `BUSINESS_SUCCESS_CODES=[200,0,'200','0']`；`/auth/context/activate` 强校验 `String(code)==='0'`，故统一取 0） |
| 错误消息 | 前端读 `message ?? msg`（后端两字段同值） |
| 分页请求 | GET query：`page`（1 起）、`pageSize`；上限 200 |
| 分页响应 | `data: { list, total, page, pageSize }` |
| 时间格式 | `yyyy-MM-dd HH:mm:ss` 字符串 |
| 未登录 | HTTP 401 + `code=401`；无权限 HTTP 403 + `code=403`；限流 HTTP 429 + `code=429` |

## 1. 认证域（robot-auth，经 `/auth/**`）

### POST /auth/login
请求：`{ "username": "admin", "password": "admin123", "contextId": "可选，登录页预选公司" }`

响应 A（直接会话：单公司，或 contextId 合法）：
```json
{ "code": 0, "data": {
  "token": "...", "refreshToken": "...", "expiresIn": 1800,
  "availableContexts": [AuthContext], "activeContext": AuthContext,
  "user": { "id": "...", "username": "admin", "displayName": "系统管理员" } } }
```
响应 B（待激活：多公司未选择）：
```json
{ "code": 0, "data": {
  "token": "", "refreshToken": "", "expiresIn": 0,
  "loginTicket": "一次性票据(5分钟)",
  "availableContexts": [AuthContext(无 roles)] } }
```

`AuthContext`：`{ id(contextId), isPrimary, tenantId, tenantName, companyId, companyName, roles:[{id,name}] }`

### POST /auth/login-companies
请求：`{ "username": "admin" }` → 响应：`data: { companies: [{ id, isPrimary, tenantName, companyName }] }`
（未知/停用账号返回空数组 —— 契约要求，防枚举）

### POST /auth/context/activate
请求：`{ "loginTicket": "...", "contextId": "..." }` → 完整会话（响应 A 形态）。票据一次性，激活即焚。

### POST /auth/context/switch
请求：`{ "contextId": "..." }`（需有效登录态）→ 新完整会话。

### POST /auth/refresh-token
请求：`{ "refreshToken": "..." }` → `data: { token, refreshToken, expiresIn }`。旧刷新令牌即作废（轮换）。

### GET /auth/menu-list
→ `data: [ { path, name, component, redirect, meta:{title,requiresAuth,roles,icon,sortOrder,keepAlive,hideMenu}, children:[...] } ]`

### GET /auth/button-list
→ `data: { "/sys-manage/user-manage": ["sys:user:list","sys:user:save", ...], ... }`

### GET /auth/data-permission
→ `data: [ { "module": "*", "scope": "all|department|department_below|self|custom" } ]`

### POST /auth/logout
→ 注销当前会话；会话内登记的刷新令牌一并作废（防登出后凭旧 refresh 复活）。轮换语义：刷新时旧 access token 立即失效（logoutByTokenValue）。

## 2. 系统管理域（robot-system，经 `/sys/**`，均需登录态）

| 方法 | 路径 | 权限码 | 说明 |
|---|---|---|---|
| GET | `/sys/users?page&pageSize&keyword&status&deptId` | sys:user:list | 分页；keyword 匹配用户名/昵称/邮箱 |
| POST | `/sys/users/{id}/reset-password` | sys:user:resetPassword | 管理员重置密码 `{password}` |
| PUT | `/sys/users/{id}/status` | sys:user:update | 启停 `{status:0/1}`；内置 admin/自身禁操作 |
| GET | `/sys/users/{id}` | sys:user:query | 详情（无 passwordHash） |
| POST | `/sys/users` | sys:user:save | 新增（password 必填，BCrypt 入库；自动加入当前公司） |
| PUT | `/sys/users/{id}` | sys:user:update | 编辑（password 留空=不改） |
| DELETE | `/sys/users/{id}` | sys:user:delete | 软删 + 成员/角色同步失效 |
| GET | `/sys/departments` | sys:user:list | 当前公司部门树 |
| GET | `/sys/roles?page&pageSize&keyword&status` | sys:role:list | 分页 |
| GET | `/sys/roles/options` | 登录即可 | 下拉 `{id,name,code}` |
| GET | `/sys/roles/{id}` | sys:role:query | 详情含 menuIds |
| GET | `/sys/roles/{id}/users` | sys:role:query | 成员清单 |
| GET | `/sys/roles/{id}/data-scopes` | sys:role:query | 数据范围 |
| GET | `/sys/roles/{id}/temp-authorizations` | sys:role:query | 临时授权 |
| POST | `/sys/roles` | sys:role:save | 新增（roleCode 大写下划线唯一） |
| PUT | `/sys/roles/{id}` | sys:role:update | 编辑 + 菜单重授权 |
| DELETE | `/sys/roles/{id}` | sys:role:delete | 有成员绑定 40903 / SUPER_ADMIN 角色禁删 |
| PUT | `/sys/roles/{id}/permissions` | sys:role:update | 权限批量授权 `{permissionIds}` |
| GET | `/sys/roles/temp-authorizations` | sys:permission:list | 治理页全量 |
| POST | `/sys/roles/temp-authorizations` | sys:permission:tempAuthorize | 30 天上限 + 理由必填 |
| GET | `/sys/menus` | sys:menu:list | 菜单树（目录/菜单，含 keepAlive/visible） |
| GET | `/sys/menus/{menuId}/buttons` | sys:menu:list | 菜单按钮 |
| POST | `/sys/menus` | sys:menu:save | 新增（JSON 与 multipart/form-data 双形态，兼容前端 FormData） |
| PUT | `/sys/menus/{id}` | sys:menu:update | 编辑（JSON 与 FormData 双形态） |
| PUT | `/sys/menus/{id}/move` | sys:menu:update | 拖拽排序 `{targetId, position: inner/before/after}`（含环检测） |
| DELETE | `/sys/menus/{id}` | sys:menu:delete | 有子菜单 40402 拦截 |
| POST | `/sys/menus/buttons` | sys:menu:save | 新增按钮权限 |
| GET | `/sys/menu-buttons` | sys:menu:list | 全量按钮 |
| POST | `/sys/menu-buttons` | sys:menu:save | 新增按钮 |
| PUT | `/sys/menu-buttons/{id}` | sys:menu:save | 编辑按钮 |
| DELETE | `/sys/menu-buttons/{id}` | sys:menu:delete | 删除按钮 |
| GET | `/sys/dictionaries` | sys:dict:list | 类型+项两级树 |
| PUT | `/sys/dictionaries/{id}` | sys:dict:save | 更新（类型或项） |
| PUT | `/sys/dictionaries/{id}/status` | sys:dict:save | 启停字典项 |
| GET | `/sys/dictionaries/by-code?code=` | 登录即可 | 按编码取项（缓存） |
| POST | `/sys/dictionaries` | sys:dict:save | 新增（type/item 双形态） |
| DELETE | `/sys/dictionaries/{id}` | sys:dict:delete | 软删（类型或项） |
| GET | `/sys/permissions` | sys:permission:list | 权限码清单 |
| GET | `/sys/permissions/tree` | sys:permission:list | 权限树（菜单+按钮） |
| GET | `/sys/data-permissions` | sys:permission:list | 数据权限配置 |
| GET | `/sys/permission-constraints` | sys:permission:list | 约束规则 |
| GET | `/sys/permission-audit-logs` | sys:permission:list | 审计日志（500 条） |
| GET | `/sys/temp-authorizations` | sys:permission:list | 全局临时授权（治理页契约路径） |
| POST | `/sys/temp-authorizations` | sys:permission:tempAuthorize | 创建临时授权 |

## 2.5 账户自助域（robot-system，经 `/account/**`，登录态操作自身数据）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/account/profile` | 个人资料（含最近登录时间/IP，取自 sys_login_log） |
| PUT | `/account/profile` | 更新资料 `{nickname,email,phone}` |
| PUT | `/account/password` | 改密 `{oldPassword,newPassword}`（校验旧密码） |
| GET | `/account/login-records` | 登录记录（最近 20 条，sys_login_log） |
| GET | `/account/security-settings` | 安全设置项（会话/轮换策略派生） |
| GET | `/account/activity-logs` | 操作动态（映射权限审计日志） |

## 3. 内部接口（/inner/**，仅服务间，网关屏蔽外网）

| 方法 | 路径 | 消费方 |
|---|---|---|
| GET | `/inner/user/by-username?username=` | robot-auth 登录 |
| GET | `/inner/user/{userId}` | robot-auth 票据/刷新链路复验 |
| GET | `/inner/user/{userId}/contexts` | robot-auth 上下文发现 |
| GET | `/inner/menu/{companyId}/routes?userId=` | robot-auth 菜单聚合 |
| GET | `/inner/menu/{companyId}/button-list?userId=` | robot-auth 按钮聚合 |
| GET | `/inner/permission/{companyId}/data-scope?userId=` | robot-auth 数据权限聚合 |
| POST | `/inner/log/login-record` | 登录日志打点（robot-auth → robot-system，尽力而为） |

> `/inner/**` 双重防护：网关屏蔽外部访问 + `X-Inner-Call` 服务间密钥校验（`ROBOT_INNER_SECRET`）。

## 4. 错误码字典（ResultCode）

| code | 语义 |
|---|---|
| 0 | 成功 |
| 400 / 401 / 403 / 404 / 429 / 500 | 参数无效 / 未登录 / 无权限 / 资源不存在 / 限流 / 系统繁忙 |
| 40101~40106 | 登录失败 / 停用 / 票据无效 / 上下文拒绝 / 刷新令牌无效 / 未关联公司 |
| 40201~40203 | 用户：不存在 / 用户名重复 / 密码弱 |
| 40301~40302 | 角色：不存在 / 编码重复 |
| 40401~40402 | 菜单：不存在 / 有子菜单 |
| 40501~40502 | 部门：不存在 / 有子部门 |
| 40601~40602 | 字典：不存在 / 类型重复 |
| 40901~40903 | 数据重复 / 乐观锁冲突 / 被引用禁删 |
