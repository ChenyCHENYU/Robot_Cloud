-- =====================================================================
-- V4：权限码种子补齐（用户重置密码 / 角色授权 / 菜单拖拽等管理动作按钮码）
-- 说明：V2 已发布不可修改（Flyway 唯一写入者），追加迁移补齐按钮权限码
-- =====================================================================

INSERT INTO sys_menu_button (id, menu_id, button_name, perm_code, sort_no, status_flag, create_user_no, create_date_time, delete_flag, revision) VALUES
('btn-user-query',   'menu-sys-user', '查询用户',     'sys:user:query',          2, 1, 'SYSTEM', NOW(), 1, 0),
('btn-user-reset',   'menu-sys-user', '重置密码',     'sys:user:resetPassword',  3, 1, 'SYSTEM', NOW(), 1, 0),
('btn-role-query',   'menu-sys-role', '查询角色',     'sys:role:query',          2, 1, 'SYSTEM', NOW(), 1, 0),
('btn-menu-query',   'menu-sys-menu', '查询菜单',     'sys:menu:query',          2, 1, 'SYSTEM', NOW(), 1, 0),
('btn-dict-query',   'menu-sys-dict', '查询字典',     'sys:dict:query',          2, 1, 'SYSTEM', NOW(), 1, 0),
('btn-perm-list',    'menu-sys',      '权限治理查看', 'sys:permission:list',     0, 1, 'SYSTEM', NOW(), 1, 0);
