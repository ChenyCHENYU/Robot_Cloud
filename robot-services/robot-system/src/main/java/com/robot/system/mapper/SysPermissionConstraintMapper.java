package com.robot.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.robot.system.api.entity.SysPermissionConstraint;
import org.apache.ibatis.annotations.Mapper;

/** 当前公司只读治理约束持久化接口，沿用该聚合的有效标记和作用域规则。 */
@Mapper
public interface SysPermissionConstraintMapper extends BaseMapper<SysPermissionConstraint> {}
