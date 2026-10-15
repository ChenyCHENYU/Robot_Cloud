package com.robot.system.role.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.robot.system.api.entity.SysTempAuthorization;
import org.apache.ibatis.annotations.Mapper;

/** 公司临时角色授权持久化接口，沿用该聚合的有效标记和作用域规则。 */
@Mapper
public interface SysTempAuthorizationMapper extends BaseMapper<SysTempAuthorization> {}
