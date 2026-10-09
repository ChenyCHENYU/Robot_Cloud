DROP ALL OBJECTS;
CREATE TABLE sys_user (
  id VARCHAR(64) PRIMARY KEY, username VARCHAR(128), password_hash VARCHAR(255), nickname VARCHAR(128),
  email VARCHAR(128), phone VARCHAR(32), department_id VARCHAR(64), status_flag INT, security_version BIGINT DEFAULT 0,
  create_user_no VARCHAR(64), create_date_time TIMESTAMP, update_user_no VARCHAR(64),
  update_date_time TIMESTAMP, delete_flag INT DEFAULT 1, revision INT DEFAULT 0
);
CREATE TABLE sys_tenant (
  id VARCHAR(64) PRIMARY KEY, tenant_name VARCHAR(128), status_flag INT, remark VARCHAR(255),
  create_user_no VARCHAR(64), create_date_time TIMESTAMP, update_user_no VARCHAR(64),
  update_date_time TIMESTAMP, delete_flag INT DEFAULT 1, revision INT DEFAULT 0
);
CREATE TABLE sys_company (
  id VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(64), company_name VARCHAR(128), status_flag INT,
  create_user_no VARCHAR(64), create_date_time TIMESTAMP, update_user_no VARCHAR(64),
  update_date_time TIMESTAMP, delete_flag INT DEFAULT 1, revision INT DEFAULT 0
);
CREATE TABLE sys_user_company (
  id VARCHAR(64) PRIMARY KEY, user_id VARCHAR(64), company_id VARCHAR(64), primary_flag INT, status_flag INT, security_version BIGINT DEFAULT 0,
  create_user_no VARCHAR(64), create_date_time TIMESTAMP, update_user_no VARCHAR(64),
  update_date_time TIMESTAMP, delete_flag INT DEFAULT 1, revision INT DEFAULT 0
);
CREATE TABLE sys_role (
  id VARCHAR(64) PRIMARY KEY, company_id VARCHAR(64), role_name VARCHAR(128), role_code VARCHAR(128),
  data_scope VARCHAR(32), sort_no INT, status_flag INT, remark VARCHAR(255),
  create_user_no VARCHAR(64), create_date_time TIMESTAMP, update_user_no VARCHAR(64),
  update_date_time TIMESTAMP, delete_flag INT DEFAULT 1, revision INT DEFAULT 0
);
CREATE TABLE sys_user_role (
  id VARCHAR(64) PRIMARY KEY, user_id VARCHAR(64), company_id VARCHAR(64), role_id VARCHAR(64),
  create_user_no VARCHAR(64), create_date_time TIMESTAMP, update_user_no VARCHAR(64),
  update_date_time TIMESTAMP, delete_flag INT DEFAULT 1, revision INT DEFAULT 0
);
CREATE TABLE sys_login_log (
  id VARCHAR(64) PRIMARY KEY, user_id VARCHAR(64), company_id VARCHAR(64), username VARCHAR(128),
  login_type VARCHAR(32), status_flag INT, ip VARCHAR(64), user_agent VARCHAR(255), login_time TIMESTAMP,
  create_user_no VARCHAR(64), create_date_time TIMESTAMP, update_user_no VARCHAR(64),
  update_date_time TIMESTAMP, delete_flag INT DEFAULT 1, revision INT DEFAULT 0
);
INSERT INTO sys_tenant (id,tenant_name,status_flag) VALUES ('t1','Enabled',1),('t2','Disabled',0);
INSERT INTO sys_company (id,tenant_id,company_name,status_flag) VALUES
  ('c1','t1','Company 1',1),('c2','t1','Company 2',1),('c3','t1','Disabled company',0),
  ('c4','t2','Disabled tenant company',1),('c5','t1','Company 5',1);
INSERT INTO sys_user (id,username,password_hash,status_flag) VALUES
  ('u1','alice','test-hash',1),('u2','bob','test-hash',1);
INSERT INTO sys_user_company (id,user_id,company_id,primary_flag,status_flag) VALUES
  ('m1','u1','c1',1,1),('m2','u1','c2',0,1),('m3','u1','c3',0,1),('m4','u1','c4',0,1),
  ('m5','u1','c5',0,0),('other','u2','c1',1,1);
INSERT INTO sys_role (id,company_id,role_name,role_code,status_flag) VALUES
  ('r1','c1','Role1','role1',1),('r2','c2','Role2','role2',1),('r3','c3','Role3','role3',1),
  ('forged','c2','Wrong company binding','must_not_grant',1),('unbound','c1','Unbound','unbound',1);
INSERT INTO sys_user_role (id,user_id,company_id,role_id) VALUES
  ('b1','u1','c1','r1'),('b2','u1','c2','r2'),('b3','u1','c3','r3'),
  ('bad','u1','c1','forged'),('other','u2','c1','unbound');
