package com.robot.system.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.robot.system.api.entity.SysUserRole;
import org.apache.ibatis.annotations.Mapper;

/** 公司成员永久角色绑定持久化接口，沿用该聚合的有效标记和作用域规则。 */
@Mapper
public interface SysUserRoleMapper extends BaseMapper<SysUserRole> {}
