package com.robot.system.user.service.context;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.robot.api.system.dto.RemoteUserContextDTO;
import com.robot.common.core.constant.RobotConstants;
import com.robot.common.mybatis.tenant.TenantQueryScope;
import com.robot.common.security.exception.InnerAccessDeniedException;
import com.robot.common.security.util.AuthUtil;
import com.robot.system.api.entity.SysCompany;
import com.robot.system.api.entity.SysRole;
import com.robot.system.api.entity.SysTenant;
import com.robot.system.api.entity.SysUserCompany;
import com.robot.system.api.entity.SysUserRole;
import com.robot.system.mapper.SysCompanyMapper;
import com.robot.system.mapper.SysTenantMapper;
import com.robot.system.role.mapper.SysRoleMapper;
import com.robot.system.security.InnerSystemAccess;
import com.robot.system.user.mapper.SysUserCompanyMapper;
import com.robot.system.user.mapper.SysUserRoleMapper;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 用户上下文装配器：成员关系 → 公司/租户信息 + 该公司下角色清单。 成员/公司/租户/角色全部批量查询，与上下文数量无关； 公司或租户缺失/停用直接过滤，防止其继续作为可登录上下文。 */
@Component
@RequiredArgsConstructor
public class UserContextAssembler {

    private final SysCompanyMapper companyMapper;
    private final SysTenantMapper tenantMapper;
    private final SysRoleMapper roleMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysUserCompanyMapper userCompanyMapper;

    /**
     * 装配单个上下文；公司无效时返回 null（调用方过滤）。
     *
     * @param userId 平台账号ID
     * @param membership 当前有效公司成员事实
     * @return 当前事实派生的结果
     */
    public RemoteUserContextDTO assemble(String userId, SysUserCompany membership) {
        List<RemoteUserContextDTO> contexts = assembleAll(userId, List.of(membership));
        return contexts.isEmpty() ? null : contexts.get(0);
    }

    /**
     * 批量装配：公司、租户、角色绑定、角色实体各一次查询。
     *
     * <p>RC07：该装配服务于登录前内部供给（无登录态/委托态），租户行级过滤 会向角色查询追加 company_id = NULL 导致查空；故在受控作用域内关闭租户行——
     * 查询自带显式谓词（成员关系的公司集合 + 角色 companyId 分组）， 并受 /inner/** 密钥校验保护（禁止全局白名单修复）。
     *
     * @param userId 平台账号ID
     * @param memberships 待复验的成员事实集合
     * @return 当前事实派生的结果
     * @throws InnerAccessDeniedException 当前身份/状态不满足用例约束或受控操作失败
     */
    public List<RemoteUserContextDTO> assembleAll(String userId, List<SysUserCompany> memberships) {
        var context = AuthUtil.requireTrustedService();
        if (context.hasDelegate() && !Objects.equals(userId, AuthUtil.getLoginUserId())) {
            throw new InnerAccessDeniedException();
        }
        if (memberships.isEmpty()) {
            return List.of();
        }
        if (memberships.stream()
                .anyMatch(
                        membership ->
                                membership == null
                                        || !Objects.equals(userId, membership.getUserId())
                                        || membership.getId() == null
                                        || membership.getCompanyId() == null
                                        || !InnerSystemAccess.active(
                                                membership.getDeleteFlag(),
                                                membership.getStatusFlag()))) {
            throw new InnerAccessDeniedException();
        }
        // 以当前数据库中的有效成员关系为准，不能由传入实体制造跨公司集合。
        List<SysUserCompany> activeMemberships =
                userCompanyMapper.selectList(
                        new LambdaQueryWrapper<SysUserCompany>()
                                .eq(SysUserCompany::getUserId, userId)
                                .in(
                                        SysUserCompany::getId,
                                        memberships.stream().map(SysUserCompany::getId).toList())
                                .eq(SysUserCompany::getStatusFlag, 1)
                                .eq(
                                        SysUserCompany::getDeleteFlag,
                                        RobotConstants.DELETE_FLAG_ACTIVE));
        if (activeMemberships.isEmpty()) {
            return List.of();
        }
        return TenantQueryScope.withoutTenantLine(() -> doAssemble(userId, activeMemberships));
    }

    private List<RemoteUserContextDTO> doAssemble(String userId, List<SysUserCompany> memberships) {
        Map<String, SysCompany> companies = activeCompanies(memberships);
        Map<String, SysTenant> tenants = activeTenants(companies);
        Set<String> availableCompanyIds =
                companies.values().stream()
                        .filter(company -> tenants.containsKey(company.getTenantId()))
                        .map(SysCompany::getId)
                        .collect(Collectors.toSet());
        if (availableCompanyIds.isEmpty()) {
            return List.of();
        }
        Map<String, List<SysRole>> roles = activeRolesByCompany(userId, availableCompanyIds);
        List<RemoteUserContextDTO> contexts =
                memberships.stream()
                        .filter(
                                membership ->
                                        availableCompanyIds.contains(membership.getCompanyId()))
                        .map(membership -> toContext(membership, companies, tenants, roles))
                        .toList();
        selectEffectivePrimary(contexts);
        return contexts;
    }

    private Map<String, SysCompany> activeCompanies(List<SysUserCompany> memberships) {
        List<String> companyIds =
                memberships.stream().map(SysUserCompany::getCompanyId).distinct().toList();
        return companyMapper.selectBatchIds(companyIds).stream()
                .filter(
                        company ->
                                InnerSystemAccess.active(
                                        company.getDeleteFlag(), company.getStatusFlag()))
                .collect(Collectors.toMap(SysCompany::getId, Function.identity()));
    }

    private Map<String, SysTenant> activeTenants(Map<String, SysCompany> companies) {
        List<String> tenantIds =
                companies.values().stream()
                        .map(SysCompany::getTenantId)
                        .filter(id -> id != null && !id.isBlank())
                        .distinct()
                        .toList();
        return tenantIds.isEmpty()
                ? Map.of()
                : tenantMapper.selectBatchIds(tenantIds).stream()
                        .filter(
                                tenant ->
                                        InnerSystemAccess.active(
                                                tenant.getDeleteFlag(), tenant.getStatusFlag()))
                        .collect(Collectors.toMap(SysTenant::getId, Function.identity()));
    }

    private Map<String, List<SysRole>> activeRolesByCompany(String userId, Set<String> companyIds) {
        List<SysUserRole> bindings =
                userRoleMapper.selectList(
                        new LambdaQueryWrapper<SysUserRole>()
                                .eq(SysUserRole::getUserId, userId)
                                .in(SysUserRole::getCompanyId, companyIds)
                                .eq(SysUserRole::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE));
        Map<String, Set<String>> boundIdsByCompany =
                bindings.stream()
                        .collect(
                                Collectors.groupingBy(
                                        SysUserRole::getCompanyId,
                                        Collectors.mapping(
                                                SysUserRole::getRoleId, Collectors.toSet())));
        Set<String> boundRoleIds =
                bindings.stream().map(SysUserRole::getRoleId).collect(Collectors.toSet());
        return boundRoleIds.isEmpty()
                ? Map.of()
                : roleMapper
                        .selectList(
                                new LambdaQueryWrapper<SysRole>()
                                        .in(SysRole::getId, boundRoleIds)
                                        .in(SysRole::getCompanyId, companyIds)
                                        .eq(
                                                SysRole::getDeleteFlag,
                                                RobotConstants.DELETE_FLAG_ACTIVE)
                                        .eq(SysRole::getStatusFlag, 1))
                        .stream()
                        .filter(
                                role ->
                                        boundIdsByCompany
                                                .getOrDefault(role.getCompanyId(), Set.of())
                                                .contains(role.getId()))
                        .collect(Collectors.groupingBy(SysRole::getCompanyId));
    }

    private RemoteUserContextDTO toContext(
            SysUserCompany membership,
            Map<String, SysCompany> companies,
            Map<String, SysTenant> tenants,
            Map<String, List<SysRole>> roles) {
        SysCompany company = companies.get(membership.getCompanyId());
        SysTenant tenant = tenants.get(company.getTenantId());
        return RemoteUserContextDTO.builder()
                .contextId(membership.getId())
                .securityVersion(membership.getSecurityVersion())
                .departmentId(membership.getDepartmentId())
                .primaryFlag(Integer.valueOf(1).equals(membership.getPrimaryFlag()))
                .tenantId(company.getTenantId())
                .tenantName(tenant.getTenantName())
                .companyId(membership.getCompanyId())
                .companyName(company.getCompanyName())
                .roles(
                        roles.getOrDefault(membership.getCompanyId(), List.of()).stream()
                                .map(
                                        role ->
                                                RemoteUserContextDTO.RoleItem.builder()
                                                        .id(role.getId())
                                                        .code(role.getRoleCode())
                                                        .name(role.getRoleName())
                                                        .build())
                                .toList())
                .build();
    }

    private void selectEffectivePrimary(List<RemoteUserContextDTO> contexts) {
        // 仅从有效响应派生，不回写存储标记或切换已有会话。
        var order = java.util.Comparator.comparing(RemoteUserContextDTO::getContextId);
        RemoteUserContextDTO primary =
                contexts.stream()
                        .filter(context -> Boolean.TRUE.equals(context.getPrimaryFlag()))
                        .min(order)
                        .orElseGet(() -> contexts.stream().min(order).orElse(null));
        contexts.forEach(context -> context.setPrimaryFlag(context == primary));
    }
}
