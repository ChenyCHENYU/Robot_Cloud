package com.robot.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.robot.system.api.entity.SysCompany;
import org.apache.ibatis.annotations.Mapper;

/** 公司事实持久化接口，沿用该聚合的有效标记和作用域规则。 */
@Mapper
public interface SysCompanyMapper extends BaseMapper<SysCompany> {}
