package com.robot.system.api.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.robot.common.mybatis.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/** 平台用户（全局表：登录态跨公司，数据经 sys_user_company 关联）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user")
public class SysUser extends BaseEntity {

    @java.io.Serial private static final long serialVersionUID = 1L;

    private String username;

    /** BCrypt 密码摘要，禁止明文。 */
    @ToString.Exclude private String passwordHash;

    /** 全局平台目录属主；普通账号/公司角色接口不能写入。 */
    private Integer platformAdminFlag = 0;

    private String nickname;

    @ToString.Exclude private String email;

    @ToString.Exclude private String phone;

    private String departmentId;

    /** 1=启用，0=停用（停用账号禁止登录）。 */
    private Integer statusFlag;

    /** 账号撤销版本，只增不减，与业务 revision 分离。 */
    private Long securityVersion = 0L;
}
