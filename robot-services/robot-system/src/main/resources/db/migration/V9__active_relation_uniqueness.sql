-- 上线前执行文档中重复活跃记录预检，发现冲突时先人工裁决；禁止自动删除历史授权。
ALTER TABLE sys_role ADD COLUMN active_role_code VARCHAR(50)
    GENERATED ALWAYS AS (CASE WHEN delete_flag = 1 THEN role_code ELSE NULL END) STORED;
ALTER TABLE sys_role ADD UNIQUE KEY uk_sys_role_active_code (company_id, active_role_code);

ALTER TABLE sys_user_company ADD COLUMN active_user_id VARCHAR(64)
    GENERATED ALWAYS AS (CASE WHEN delete_flag = 1 THEN user_id ELSE NULL END) STORED;
ALTER TABLE sys_user_company ADD UNIQUE KEY uk_sys_member_active (company_id, active_user_id);
ALTER TABLE sys_user_company ADD COLUMN primary_user_id VARCHAR(64)
    GENERATED ALWAYS AS (CASE WHEN delete_flag = 1 AND primary_flag = 1 THEN user_id ELSE NULL END) STORED;
ALTER TABLE sys_user_company ADD UNIQUE KEY uk_sys_member_primary (primary_user_id);

ALTER TABLE sys_user_role ADD COLUMN active_role_id VARCHAR(64)
    GENERATED ALWAYS AS (CASE WHEN delete_flag = 1 THEN role_id ELSE NULL END) STORED;
ALTER TABLE sys_user_role ADD UNIQUE KEY uk_sys_user_role_active (company_id, user_id, active_role_id);

ALTER TABLE sys_role_menu ADD COLUMN active_menu_id VARCHAR(64)
    GENERATED ALWAYS AS (CASE WHEN delete_flag = 1 THEN menu_id ELSE NULL END) STORED;
ALTER TABLE sys_role_menu ADD UNIQUE KEY uk_sys_role_menu_active (role_id, active_menu_id);
