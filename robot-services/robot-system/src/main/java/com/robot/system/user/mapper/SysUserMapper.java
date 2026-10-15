package com.robot.system.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.robot.system.api.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;

/** 用户 Mapper：单表 CRUD 由 MP 提供，复杂查询走 XML。 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {}
