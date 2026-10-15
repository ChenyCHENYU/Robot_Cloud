package com.robot.system.role.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.robot.system.api.entity.SysRole;
import org.apache.ibatis.annotations.Mapper;

/** 公司角色持久化接口，沿用该聚合的有效标记和作用域规则。 */
@Mapper
public interface SysRoleMapper extends BaseMapper<SysRole> {}
