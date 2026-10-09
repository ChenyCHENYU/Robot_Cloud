package com.robot.system.api.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.robot.common.mybatis.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 菜单按钮级权限（前端 /sys/menu-buttons 管理对象）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_menu_button")
public class SysMenuButton extends BaseEntity {

    private String menuId;

    private String buttonName;

    /** 按钮权限码（如 sys:user:save）。 */
    private String permCode;

    private Integer sortNo;

    /** 1=启用，0=停用。 */
    private Integer statusFlag;

    private String remark;
}
