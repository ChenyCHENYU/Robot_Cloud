DROP ALL OBJECTS;
CREATE TABLE sys_user (
  id VARCHAR(64) PRIMARY KEY, username VARCHAR(30), password_hash VARCHAR(255), nickname VARCHAR(30),
  email VARCHAR(100), phone VARCHAR(20), department_id VARCHAR(64), status_flag INT, security_version BIGINT DEFAULT 0,
  create_user_no VARCHAR(64), create_date_time TIMESTAMP, update_user_no VARCHAR(64),
  update_date_time TIMESTAMP, delete_flag INT DEFAULT 1, revision INT DEFAULT 0,
  platform_admin_flag INT DEFAULT 0
);
CREATE TABLE sys_user_company (
  id VARCHAR(64) PRIMARY KEY, user_id VARCHAR(64), company_id VARCHAR(64), primary_flag INT,
  status_flag INT, security_version BIGINT DEFAULT 0, create_user_no VARCHAR(64), create_date_time TIMESTAMP,
  update_user_no VARCHAR(64), update_date_time TIMESTAMP, delete_flag INT DEFAULT 1, revision INT DEFAULT 0,
  department_id VARCHAR(64)
);
CREATE TABLE sys_user_role (
  id VARCHAR(64) PRIMARY KEY, user_id VARCHAR(64), company_id VARCHAR(64), role_id VARCHAR(64),
  create_user_no VARCHAR(64), create_date_time TIMESTAMP, update_user_no VARCHAR(64),
  update_date_time TIMESTAMP, delete_flag INT DEFAULT 1, revision INT DEFAULT 0
);
CREATE TABLE sys_temp_authorization (
  id VARCHAR(64) PRIMARY KEY, user_id VARCHAR(64), company_id VARCHAR(64), role_id VARCHAR(64),
  effect_from TIMESTAMP, effect_to TIMESTAMP, reason VARCHAR(255), status_flag INT,
  create_user_no VARCHAR(64), create_date_time TIMESTAMP, update_user_no VARCHAR(64),
  update_date_time TIMESTAMP, delete_flag INT DEFAULT 1, revision INT DEFAULT 0
);
CREATE TABLE sys_role (
  id VARCHAR(64) PRIMARY KEY, company_id VARCHAR(64), role_name VARCHAR(30), role_code VARCHAR(50),
  data_scope VARCHAR(30), sort_no INT, status_flag INT, remark VARCHAR(255),
  create_user_no VARCHAR(64), create_date_time TIMESTAMP, update_user_no VARCHAR(64),
  update_date_time TIMESTAMP, delete_flag INT DEFAULT 1, revision INT DEFAULT 0
);
CREATE TABLE sys_department (
  id VARCHAR(64) PRIMARY KEY, company_id VARCHAR(64), parent_id VARCHAR(64), dept_name VARCHAR(60),
  sort_no INT, status_flag INT, create_user_no VARCHAR(64), create_date_time TIMESTAMP,
  update_user_no VARCHAR(64), update_date_time TIMESTAMP, delete_flag INT DEFAULT 1, revision INT DEFAULT 0
);
INSERT INTO sys_department (id,company_id,parent_id,dept_name,status_flag) VALUES
  ('d-own','c1',NULL,'Own',1),('d-peer','c1',NULL,'Neighbour',1),
  ('d-child','c1','d-own','Child',1),('d-foreign','c2',NULL,'Foreign',1);
INSERT INTO sys_user (id,username,nickname,status_flag,department_id) VALUES
  ('u1','target','Target',1,'d-own'),('u2','peer','Peer',1,'d-peer'),
  ('u3','child','Child',1,'d-child'),('u4','foreigndept','Foreign department',1,'d-foreign'),
  ('u5','same','Same department',1,'d-own'),('u6','othercompany','Other company',1,'d-foreign');
INSERT INTO sys_user_company (id,user_id,company_id,primary_flag,status_flag) VALUES
  ('m1','u1','c1',1,1),('m-peer','u2','c1',1,1),('m-child','u3','c1',1,1),
  ('m-foreign-dept','u4','c1',1,1),('m-same','u5','c1',1,1),('m-other','u6','c2',1,1);
INSERT INTO sys_role (id,company_id,role_name,role_code,status_flag) VALUES ('r1','c1','Staff','STAFF',1);
INSERT INTO sys_user_role (id,user_id,company_id,role_id) VALUES ('b1','u1','c1','r1');
INSERT INTO sys_temp_authorization (id,user_id,company_id,role_id,status_flag) VALUES
  ('temp1','u1','c1','r1',1),('temp2','u1','c2','r2',1);

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

CREATE TABLE sys_permission_audit_log (
 id VARCHAR(64) PRIMARY KEY, company_id VARCHAR(64), actor_user_no VARCHAR(64), action VARCHAR(64),
 target VARCHAR(255), detail VARCHAR(2000), create_user_no VARCHAR(64), create_date_time TIMESTAMP,
 update_user_no VARCHAR(64), update_date_time TIMESTAMP, delete_flag INT DEFAULT 1, revision INT DEFAULT 0
);
CREATE TABLE sys_cache_epoch(scope VARCHAR(255) PRIMARY KEY, version BIGINT NOT NULL);
INSERT INTO sys_cache_epoch VALUES('permissions',1),('menu-tree',1);
CREATE TABLE sys_cache_outbox (
 id VARCHAR(64) PRIMARY KEY, event_kind VARCHAR(16), cache_key VARCHAR(255), cache_version BIGINT,
 created_at TIMESTAMP, next_attempt_at TIMESTAMP, completed_at TIMESTAMP, attempts INT,
 last_error VARCHAR(255), delivery_revision BIGINT NOT NULL DEFAULT 0, last_failure_id VARCHAR(36), quarantined_at TIMESTAMP, archived_at TIMESTAMP);
INSERT INTO sys_role(id,company_id,role_name,role_code,data_scope,status_flag,revision) VALUES
 ('r-free','c1','Free','FREE','self',1,4),('r-other','c2','Other','OTHER','all',1,0);
INSERT INTO sys_menu(id,menu_type,menu_name,path,status_flag) VALUES
 ('page-a','menu','Page A','/a',1),('page-b','menu','Page B','/b',1);
INSERT INTO sys_menu_button(id,menu_id,button_name,perm_code,status_flag) VALUES
 ('button-a','page-a','Action A','sys:fixture:a',1);
INSERT INTO sys_role_menu(id,role_id,menu_id) VALUES('rm-free','r-free','page-a');
INSERT INTO sys_role_button(id,role_id,button_id) VALUES('rb-free','r-free','button-a');
