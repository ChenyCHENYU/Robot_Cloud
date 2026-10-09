package com.robot.system.api.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.robot.common.mybatis.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 菜单/路由（全局表，权限按角色过滤）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_menu")
public class SysMenu extends BaseEntity {

    private String parentId;

    /** directory=目录 / menu=菜单 / button 声明在 sys_menu_button。 */
    private String menuType;

    private String menuName;

    /** 前端路由 path。 */
    private String path;

    /** 前端组件标识。 */
    private String component;

    private String redirect;

    /** 页面级权限码（如 sys:user:list）。 */
    private String permCode;

    private String icon;

    private Integer sortNo;

    /** 是否在菜单显示：1=显示，0=隐藏。 */
    private Integer visibleFlag;

    /** 页面 KeepAlive：1=缓存，0=不缓存。 */
    private Integer keepAliveFlag;

    /** 1=启用，0=停用。 */
    private Integer statusFlag;

    private String remark;
}
