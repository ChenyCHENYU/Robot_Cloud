package com.robot.system.api.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.robot.common.mybatis.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 角色-按钮精确授权（RC11：菜单授权不再隐含按钮）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_role_button")
public class SysRoleButton extends BaseEntity {

    @java.io.Serial private static final long serialVersionUID = 1L;

    private String roleId;

    private String buttonId;
}
