DROP TABLE IF EXISTS sys_dict_item;
DROP TABLE IF EXISTS sys_dict_type;
DROP TABLE IF EXISTS sys_cache_epoch;
DROP TABLE IF EXISTS sys_cache_outbox;
CREATE TABLE sys_dict_type (
 id VARCHAR(64) PRIMARY KEY, type_code VARCHAR_IGNORECASE(60) NOT NULL, type_name VARCHAR(60) NOT NULL,
 remark VARCHAR(255), status_flag INT DEFAULT 1 NOT NULL,
 create_user_no VARCHAR(64), create_date_time TIMESTAMP, update_user_no VARCHAR(64), update_date_time TIMESTAMP,
 delete_flag INT DEFAULT 1 NOT NULL, revision INT DEFAULT 0 NOT NULL
);
CREATE TABLE sys_dict_item (
 id VARCHAR(64) PRIMARY KEY, type_id VARCHAR(64) NOT NULL, item_label VARCHAR(60) NOT NULL,
 item_code VARCHAR(60) NOT NULL, item_value VARCHAR(60) NOT NULL, remark VARCHAR(255),
 sort_no INT DEFAULT 0 NOT NULL, status_flag INT DEFAULT 1 NOT NULL,
 create_user_no VARCHAR(64), create_date_time TIMESTAMP, update_user_no VARCHAR(64), update_date_time TIMESTAMP,
 delete_flag INT DEFAULT 1 NOT NULL, revision INT DEFAULT 0 NOT NULL
);
CREATE TABLE sys_cache_epoch (scope VARCHAR_IGNORECASE(255) PRIMARY KEY, version BIGINT NOT NULL);
CREATE TABLE sys_cache_outbox (id VARCHAR(64) PRIMARY KEY, event_kind VARCHAR(16), cache_key VARCHAR(255),
 cache_version BIGINT, created_at TIMESTAMP, next_attempt_at TIMESTAMP, completed_at TIMESTAMP, attempts INT, last_error VARCHAR(255),
 delivery_revision BIGINT DEFAULT 0 NOT NULL, last_failure_id VARCHAR(36));
INSERT INTO sys_dict_type (id,type_code,type_name) VALUES ('t1','state','State'),('t2','other','Other');
INSERT INTO sys_cache_epoch VALUES ('dictionary:state',1),('dictionary:other',1);
INSERT INTO sys_dict_item(id,type_id,item_label,item_code,item_value) VALUES ('i1','t1','Active','enabled','0');
