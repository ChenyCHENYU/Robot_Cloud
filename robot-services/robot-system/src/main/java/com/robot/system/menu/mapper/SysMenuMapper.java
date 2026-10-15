package com.robot.system.menu.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.robot.system.api.entity.SysMenu;
import org.apache.ibatis.annotations.Mapper;

/** 全局菜单目录持久化接口，沿用该聚合的有效标记和作用域规则。 */
@Mapper
public interface SysMenuMapper extends BaseMapper<SysMenu> {}
