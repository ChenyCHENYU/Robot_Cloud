package com.robot.system.menu.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.robot.system.api.entity.SysMenuButton;
import org.apache.ibatis.annotations.Mapper;

/** 全局页面按钮目录持久化接口，沿用该聚合的有效标记和作用域规则。 */
@Mapper
public interface SysMenuButtonMapper extends BaseMapper<SysMenuButton> {}
