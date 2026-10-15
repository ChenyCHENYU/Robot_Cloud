package com.robot.system.account.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.robot.system.api.entity.SysLoginLog;
import org.apache.ibatis.annotations.Mapper;

/** 可信服务登录审计持久化接口，沿用该聚合的有效标记和作用域规则。 */
@Mapper
public interface SysLoginLogMapper extends BaseMapper<SysLoginLog> {}
