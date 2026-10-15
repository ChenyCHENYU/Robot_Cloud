package com.robot.system.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.robot.system.api.entity.SysUserCompany;
import org.apache.ibatis.annotations.Mapper;

/** 公司成员持久化接口，沿用该聚合的有效标记和作用域规则。 */
@Mapper
public interface SysUserCompanyMapper extends BaseMapper<SysUserCompany> {}
