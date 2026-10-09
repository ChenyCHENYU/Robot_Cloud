package com.robot.system.permission.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceAssert;
import com.robot.common.security.util.AuthUtil;
import com.robot.system.api.entity.SysPermissionAuditLog;
import com.robot.system.api.entity.SysPermissionConstraint;
import com.robot.system.mapper.SysPermissionAuditLogMapper;
import com.robot.system.mapper.SysPermissionConstraintMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

/** 治理读侧：公司谓词与租户拦截器共同约束查询；约束规则目前仅供展示。 */
@Service
@RequiredArgsConstructor
public class PermissionGovernanceQueryService {
    private final SysPermissionConstraintMapper constraints;
    private final SysPermissionAuditLogMapper audits;

    /** 读取当前公司的规则配置；此接口不代表规则执行器已经实现。 */
    public List<SysPermissionConstraint> constraints() {
        return constraints.selectList(new LambdaQueryWrapper<SysPermissionConstraint>()
                .eq(SysPermissionConstraint::getCompanyId, companyId())
                .orderByAsc(SysPermissionConstraint::getRuleName).orderByAsc(SysPermissionConstraint::getId));
    }

    /** 在当前公司范围内按时间和ID稳定返回最近500条审计。 */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public List<SysPermissionAuditLog> auditLogs() {
        return audits.selectList(new LambdaQueryWrapper<SysPermissionAuditLog>()
                .eq(SysPermissionAuditLog::getCompanyId, companyId())
                .orderByDesc(SysPermissionAuditLog::getCreateDateTime).orderByDesc(SysPermissionAuditLog::getId)
                .last("LIMIT 500"));
    }

    private static String companyId() {
        String companyId = AuthUtil.getLoginCompanyId();
        ServiceAssert.notBlank(companyId, ResultCode.FORBIDDEN);
        return companyId;
    }
}
