package com.robot.system.dept.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.robot.system.api.entity.SysDepartment;
import org.apache.ibatis.annotations.Mapper;

/** 公司部门持久化接口，沿用该聚合的有效标记和作用域规则。 */
@Mapper
public interface SysDepartmentMapper extends BaseMapper<SysDepartment> {}
