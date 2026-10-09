DROP ALL OBJECTS;
CREATE TABLE sys_cache_epoch(scope VARCHAR(200) PRIMARY KEY, version BIGINT NOT NULL);
INSERT INTO sys_cache_epoch VALUES('menu-tree',1),('permissions',1);
CREATE TABLE sys_menu (
 id VARCHAR(64) PRIMARY KEY,parent_id VARCHAR(64),menu_type VARCHAR(20),menu_name VARCHAR(60),
 path VARCHAR(200),component VARCHAR(200),redirect VARCHAR(200),perm_code VARCHAR(100),icon VARCHAR(100),
 sort_no INT DEFAULT 0,visible_flag INT DEFAULT 1,keep_alive_flag INT DEFAULT 0,status_flag INT DEFAULT 1,
 remark VARCHAR(255),create_user_no VARCHAR(64),create_date_time TIMESTAMP,update_user_no VARCHAR(64),
 update_date_time TIMESTAMP,delete_flag INT DEFAULT 1,revision INT DEFAULT 0
);
CREATE TABLE sys_menu_button (
 id VARCHAR(64) PRIMARY KEY,menu_id VARCHAR(64),button_name VARCHAR(60),perm_code VARCHAR(100),
 sort_no INT DEFAULT 0,status_flag INT DEFAULT 1,remark VARCHAR(255),create_user_no VARCHAR(64),
 create_date_time TIMESTAMP,update_user_no VARCHAR(64),update_date_time TIMESTAMP,
 delete_flag INT DEFAULT 1,revision INT DEFAULT 0
);
CREATE TABLE sys_role_menu (
 id VARCHAR(64) PRIMARY KEY,role_id VARCHAR(64),menu_id VARCHAR(64),create_user_no VARCHAR(64),
 create_date_time TIMESTAMP,update_user_no VARCHAR(64),update_date_time TIMESTAMP,
 delete_flag INT DEFAULT 1,revision INT DEFAULT 0
);
CREATE TABLE sys_role_button (
 id VARCHAR(64) PRIMARY KEY,role_id VARCHAR(64),button_id VARCHAR(64),create_user_no VARCHAR(64),
 create_date_time TIMESTAMP,update_user_no VARCHAR(64),update_date_time TIMESTAMP,
 delete_flag INT DEFAULT 1,revision INT DEFAULT 0
);
INSERT INTO sys_menu(id,parent_id,menu_type,menu_name,path,component,sort_no) VALUES
 ('A',NULL,'directory','A','/a',NULL,1),('B',NULL,'directory','B','/b',NULL,1),
 ('C',NULL,'directory','C','/c',NULL,9),('P','A','menu','P','/p','/p/index',1),
 ('Q','A','menu','Q','/q','/q/index',1),('D','A','directory','D','/d',NULL,5),
 ('N','D','menu','N','/n','/n/index',1);
