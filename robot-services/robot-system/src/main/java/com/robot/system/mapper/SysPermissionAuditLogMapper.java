package com.robot.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.robot.system.api.entity.SysPermissionAuditLog;
import org.apache.ibatis.annotations.Mapper;

/** 只追加权限审计持久化接口，沿用该聚合的有效标记和作用域规则。 */
@Mapper
public interface SysPermissionAuditLogMapper extends BaseMapper<SysPermissionAuditLog> {}
