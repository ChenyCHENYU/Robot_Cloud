package com.robot.system.permission.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.robot.common.core.domain.PageResult;
import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceAssert;
import com.robot.common.security.util.AuthUtil;
import com.robot.system.api.dto.role.PermissionAuditPageQuery;
import com.robot.system.api.entity.SysPermissionAuditLog;
import com.robot.system.api.entity.SysPermissionConstraint;
import com.robot.system.api.vo.permission.PermissionAuditVO;
import com.robot.system.mapper.SysPermissionAuditLogMapper;
import com.robot.system.mapper.SysPermissionConstraintMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 治理读侧：公司谓词与租户拦截器共同约束查询；约束规则目前仅供展示。 */
@Service
@RequiredArgsConstructor
public class PermissionGovernanceQueryService {
    private final SysPermissionConstraintMapper constraints;
    private final SysPermissionAuditLogMapper audits;

    /**
     * 读取当前公司的规则配置；此接口不代表规则执行器已经实现。
     *
     * @return 当前事实派生的结果
     */
    public List<SysPermissionConstraint> constraints() {
        return constraints.selectList(
                new LambdaQueryWrapper<SysPermissionConstraint>()
                        .eq(SysPermissionConstraint::getCompanyId, companyId())
                        .orderByAsc(SysPermissionConstraint::getRuleName)
                        .orderByAsc(SysPermissionConstraint::getId)
                        .last("LIMIT 500"));
    }

    /**
     * 历史审计不再被最近500条边界永久遮蔽，过滤和分页均落SQL。
     *
     * @param query 分页与筛选条件
     * @return 当前事实派生的结果
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public PageResult<PermissionAuditVO> auditPage(PermissionAuditPageQuery query) {
        ServiceAssert.isTrue(
                query.getFrom() == null
                        || query.getTo() == null
                        || !query.getFrom().isAfter(query.getTo()),
                ResultCode.PARAM_INVALID,
                "审计开始时间不得晚于结束");
        var page =
                audits.selectPage(
                        new Page<SysPermissionAuditLog>(query.pageValue(), query.pageSizeValue()),
                        new LambdaQueryWrapper<SysPermissionAuditLog>()
                                .eq(SysPermissionAuditLog::getCompanyId, companyId())
                                .eq(
                                        query.getAction() != null,
                                        SysPermissionAuditLog::getAction,
                                        query.getAction())
                                .eq(
                                        query.getTarget() != null,
                                        SysPermissionAuditLog::getTarget,
                                        query.getTarget())
                                .ge(
                                        query.getFrom() != null,
                                        SysPermissionAuditLog::getCreateDateTime,
                                        query.getFrom())
                                .le(
                                        query.getTo() != null,
                                        SysPermissionAuditLog::getCreateDateTime,
                                        query.getTo())
                                .orderByDesc(
                                        SysPermissionAuditLog::getCreateDateTime,
                                        SysPermissionAuditLog::getId));
        return PageResult.of(
                page.getRecords().stream()
                        .map(
                                item ->
                                        new PermissionAuditVO(
                                                item.getId(),
                                                item.getActorUserNo(),
                                                item.getAction(),
                                                item.getTarget(),
                                                item.getDetail(),
                                                item.getCreateDateTime()))
                        .toList(),
                page.getTotal(),
                query.pageValue(),
                query.pageSizeValue());
    }

    /**
     * 在当前公司范围内按时间和ID稳定返回最近500条审计。
     *
     * @return 当前事实派生的结果
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public List<SysPermissionAuditLog> auditLogs() {
        return audits.selectList(
                new LambdaQueryWrapper<SysPermissionAuditLog>()
                        .eq(SysPermissionAuditLog::getCompanyId, companyId())
                        .orderByDesc(SysPermissionAuditLog::getCreateDateTime)
                        .orderByDesc(SysPermissionAuditLog::getId)
                        .last("LIMIT 500"));
    }

    private static String companyId() {
        String companyId = AuthUtil.getLoginCompanyId();
        ServiceAssert.notBlank(companyId, ResultCode.FORBIDDEN);
        return companyId;
    }
}
