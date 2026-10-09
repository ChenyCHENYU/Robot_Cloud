-- 上线前执行 docs/cache-and-dictionary-contract.md 中的重复值预检；不自动合并或删除业务数据。
ALTER TABLE sys_dict_type ADD COLUMN status_flag TINYINT NOT NULL DEFAULT 1 COMMENT '类型启停状态';
ALTER TABLE sys_dict_item ADD COLUMN item_code VARCHAR(60) NULL COMMENT '项编码，独立于显示标签与存储值';
ALTER TABLE sys_dict_item ADD COLUMN remark VARCHAR(255) NULL COMMENT '备注';
UPDATE sys_dict_item SET item_code = item_value WHERE item_code IS NULL;
ALTER TABLE sys_dict_item MODIFY COLUMN item_code VARCHAR(60) NOT NULL COMMENT '项编码';

ALTER TABLE sys_dict_type ADD COLUMN active_type_code VARCHAR(60)
    GENERATED ALWAYS AS (CASE WHEN delete_flag = 1 THEN type_code ELSE NULL END) STORED;
ALTER TABLE sys_dict_type ADD UNIQUE KEY uk_sys_dict_type_active_code (active_type_code);
ALTER TABLE sys_dict_type DROP INDEX uk_sys_dict_type_code;

ALTER TABLE sys_dict_item ADD COLUMN active_item_code VARCHAR(60)
    GENERATED ALWAYS AS (CASE WHEN delete_flag = 1 THEN item_code ELSE NULL END) STORED;
ALTER TABLE sys_dict_item ADD COLUMN active_item_value VARCHAR(60)
    GENERATED ALWAYS AS (CASE WHEN delete_flag = 1 THEN item_value ELSE NULL END) STORED;
ALTER TABLE sys_dict_item ADD UNIQUE KEY uk_sys_dict_item_active_code (type_id, active_item_code);
ALTER TABLE sys_dict_item ADD UNIQUE KEY uk_sys_dict_item_active_value (type_id, active_item_value);
