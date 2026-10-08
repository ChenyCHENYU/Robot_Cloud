-- =====================================================================
-- Robot Cloud 种子数据（V2）
-- 默认账号：admin / admin123（BCrypt 摘要），首登后必须改密
-- ID 采用固定值保证幂等与可追溯
-- =====================================================================

-- 租户与公司
INSERT INTO sys_tenant (id, tenant_name, status_flag, create_user_no, create_date_time, delete_flag, revision)
VALUES ('tnt-default', '默认租户', 1, 'SYSTEM', NOW(), 1, 0);

INSERT INTO sys_company (id, tenant_id, company_name, status_flag, create_user_no, create_date_time, delete_flag, revision)
VALUES ('cmp-robot', 'tnt-default', 'Robot 科技', 1, 'SYSTEM', NOW(), 1, 0);

-- 部门
INSERT INTO sys_department (id, company_id, parent_id, dept_name, sort_no, status_flag, create_user_no, create_date_time, delete_flag, revision)
VALUES ('dept-root', 'cmp-robot', NULL, '总公司', 0, 1, 'SYSTEM', NOW(), 1, 0),
       ('dept-dev', 'cmp-robot', 'dept-root', '研发部', 1, 1, 'SYSTEM', NOW(), 1, 0);

-- 角色（超管 + 普通管理）
INSERT INTO sys_role (id, company_id, role_name, role_code, data_scope, sort_no, status_flag, remark, create_user_no, create_date_time, delete_flag, revision)
VALUES ('role-super-admin', 'cmp-robot', '超级管理员', 'SUPER_ADMIN', 'all', 0, 1, '拥有全部权限', 'SYSTEM', NOW(), 1, 0),
       ('role-sys-admin', 'cmp-robot', '系统管理员', 'SYS_ADMIN', 'all', 1, 1, '系统管理域权限', 'SYSTEM', NOW(), 1, 0);

-- 用户（admin / admin123）
INSERT INTO sys_user (id, username, password_hash, nickname, email, department_id, status_flag, create_user_no, create_date_time, delete_flag, revision)
VALUES ('usr-admin', 'admin', '$2a$10$hFoJ4.tqHIl5h2iy5iz3POElWGTYS2mXr8SScH9IE5wHNpIxPUdtu', '系统管理员', 'admin@robot.local', 'dept-root', 1, 'SYSTEM', NOW(), 1, 0);

-- 成员关系（contextId）
INSERT INTO sys_user_company (id, user_id, company_id, primary_flag, status_flag, create_user_no, create_date_time, delete_flag, revision)
VALUES ('ctx-admin-robot', 'usr-admin', 'cmp-robot', 1, 1, 'SYSTEM', NOW(), 1, 0);

-- 角色绑定
INSERT INTO sys_user_role (id, company_id, user_id, role_id, create_user_no, create_date_time, delete_flag, revision)
VALUES ('ur-admin-1', 'cmp-robot', 'usr-admin', 'role-super-admin', 'SYSTEM', NOW(), 1, 0);

-- 菜单（对齐 Robot_Admin 前端已注册路由）
INSERT INTO sys_menu (id, parent_id, menu_type, menu_name, path, component, icon, sort_no, visible_flag, keep_alive_flag, status_flag, perm_code, create_user_no, create_date_time, delete_flag, revision) VALUES
('menu-home',      NULL,          'menu',      '首页',     '/home',       'home/index',       'home',     0, 1, 1, 1, NULL,              'SYSTEM', NOW(), 1, 0),
('menu-dashboard', NULL,          'directory', '仪表盘',   '/dashboard',  NULL,               'dashboard',1, 1, 0, 1, NULL,              'SYSTEM', NOW(), 1, 0),
('menu-dash-data', 'menu-dashboard','menu',    '数据看板', '/dashboard/data', 'dashboard/data/index', NULL, 0, 1, 0, 1, 'dashboard:data:list', 'SYSTEM', NOW(), 1, 0),
('menu-sys',       NULL,          'directory', '系统管理', '/sys-manage', NULL,               'settings', 2, 1, 0, 1, NULL,              'SYSTEM', NOW(), 1, 0),
('menu-sys-user',  'menu-sys',    'menu',      '用户管理', '/sys-manage/user-manage',  'sys-manage/user-manage/index',  NULL, 0, 1, 1, 1, 'sys:user:list',  'SYSTEM', NOW(), 1, 0),
('menu-sys-role',  'menu-sys',    'menu',      '角色管理', '/sys-manage/role-manage',  'sys-manage/role-manage/index',  NULL, 1, 1, 1, 1, 'sys:role:list',  'SYSTEM', NOW(), 1, 0),
('menu-sys-menu',  'menu-sys',    'menu',      '菜单管理', '/sys-manage/menu-manage',  'sys-manage/menu-manage/index',  NULL, 2, 1, 1, 1, 'sys:menu:list',  'SYSTEM', NOW(), 1, 0),
('menu-sys-dict',  'menu-sys',    'menu',      '字典管理', '/sys-manage/dictionary-manage', 'sys-manage/dictionary-manage/index', NULL, 3, 1, 1, 1, 'sys:dict:list', 'SYSTEM', NOW(), 1, 0);

-- 按钮权限（系统管理域）
INSERT INTO sys_menu_button (id, menu_id, button_name, perm_code, sort_no, status_flag, create_user_no, create_date_time, delete_flag, revision) VALUES
('btn-user-save',   'menu-sys-user', '新增/编辑用户', 'sys:user:save',   0, 1, 'SYSTEM', NOW(), 1, 0),
('btn-user-del',    'menu-sys-user', '删除用户',     'sys:user:delete', 1, 1, 'SYSTEM', NOW(), 1, 0),
('btn-role-save',   'menu-sys-role', '新增/编辑角色', 'sys:role:save',   0, 1, 'SYSTEM', NOW(), 1, 0),
('btn-role-del',    'menu-sys-role', '删除角色',     'sys:role:delete', 1, 1, 'SYSTEM', NOW(), 1, 0),
('btn-menu-save',   'menu-sys-menu', '新增/编辑菜单', 'sys:menu:save',   0, 1, 'SYSTEM', NOW(), 1, 0),
('btn-menu-del',    'menu-sys-menu', '删除菜单',     'sys:menu:delete', 1, 1, 'SYSTEM', NOW(), 1, 0),
('btn-dict-save',   'menu-sys-dict', '新增字典',     'sys:dict:save',   0, 1, 'SYSTEM', NOW(), 1, 0),
('btn-dict-del',    'menu-sys-dict', '删除字典',     'sys:dict:delete', 1, 1, 'SYSTEM', NOW(), 1, 0);

-- 系统管理员授权（非超管演示：系统管理域全部菜单）
INSERT INTO sys_role_menu (id, role_id, menu_id, create_user_no, create_date_time, delete_flag, revision) VALUES
('rm-1', 'role-sys-admin', 'menu-home',      'SYSTEM', NOW(), 1, 0),
('rm-2', 'role-sys-admin', 'menu-sys',       'SYSTEM', NOW(), 1, 0),
('rm-3', 'role-sys-admin', 'menu-sys-user',  'SYSTEM', NOW(), 1, 0),
('rm-4', 'role-sys-admin', 'menu-sys-role',  'SYSTEM', NOW(), 1, 0),
('rm-5', 'role-sys-admin', 'menu-sys-menu',  'SYSTEM', NOW(), 1, 0),
('rm-6', 'role-sys-admin', 'menu-sys-dict',  'SYSTEM', NOW(), 1, 0);

-- 字典种子
INSERT INTO sys_dict_type (id, type_code, type_name, remark, create_user_no, create_date_time, delete_flag, revision) VALUES
('dt-user-status', 'user_status', '用户状态', '通用启用停用状态', 'SYSTEM', NOW(), 1, 0);

INSERT INTO sys_dict_item (id, type_id, item_label, item_value, sort_no, status_flag, create_user_no, create_date_time, delete_flag, revision) VALUES
('di-user-status-1', 'dt-user-status', '启用', '1', 0, 1, 'SYSTEM', NOW(), 1, 0),
('di-user-status-0', 'dt-user-status', '停用', '0', 1, 1, 'SYSTEM', NOW(), 1, 0);

-- 默认权限约束
INSERT INTO sys_permission_constraint (id, company_id, rule_name, rule_type, config_json, status_flag, create_user_no, create_date_time, delete_flag, revision) VALUES
('pc-self-grant', 'cmp-robot', '禁止自授角色', 'self_grant_ban', '{"scope":"role_grant"}', 1, 'SYSTEM', NOW(), 1, 0),
('pc-sensitive',  'cmp-robot', '敏感操作二次确认', 'sensitive_confirm', '{"actions":["delete_user","delete_role","temp_authorize"]}', 1, 'SYSTEM', NOW(), 1, 0);
