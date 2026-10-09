package com.robot.system.user.service.context;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.robot.api.system.dto.RemoteUserContextDTO;
import com.robot.common.core.constant.RobotConstants;
import com.robot.system.api.entity.SysCompany;
import com.robot.system.api.entity.SysRole;
import com.robot.system.api.entity.SysTenant;
import com.robot.system.api.entity.SysUserCompany;
import com.robot.system.api.entity.SysUserRole;
import com.robot.common.mybatis.tenant.TenantQueryScope;
import com.robot.system.mapper.SysCompanyMapper;
import com.robot.system.mapper.SysTenantMapper;
import com.robot.system.role.mapper.SysRoleMapper;
import com.robot.system.user.mapper.SysUserRoleMapper;
import com.robot.system.user.mapper.SysUserCompanyMapper;
import com.robot.common.security.util.AuthUtil;
import com.robot.common.security.exception.InnerAccessDeniedException;
import com.robot.system.security.InnerSystemAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 用户上下文装配器：成员关系 → 公司/租户信息 + 该公司下角色清单。
 * 成员/公司/租户/角色全部批量查询，与上下文数量无关；
 * 公司或租户缺失/停用直接过滤，防止其继续作为可登录上下文。
 */
@Component
@RequiredArgsConstructor
public class UserContextAssembler {

    private final SysCompanyMapper companyMapper;
    private final SysTenantMapper tenantMapper;
    private final SysRoleMapper roleMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysUserCompanyMapper userCompanyMapper;

    /** 装配单个上下文；公司无效时返回 null（调用方过滤）。 */
    public RemoteUserContextDTO assemble(String userId, SysUserCompany membership) {
        List<RemoteUserContextDTO> contexts = assembleAll(userId, List.of(membership));
        return contexts.isEmpty() ? null : contexts.get(0);
    }

    /**
     * 批量装配：公司、租户、角色绑定、角色实体各一次查询。
     *
     * <p>RC07：该装配服务于登录前内部供给（无登录态/委托态），租户行级过滤
     * 会向角色查询追加 company_id = NULL 导致查空；故在受控作用域内关闭租户行——
     * 查询自带显式谓词（成员关系的公司集合 + 角色 companyId 分组），
     * 并受 /inner/** 密钥校验保护（禁止全局白名单修复）。
     */
    public List<RemoteUserContextDTO> assembleAll(String userId,
                                                  List<SysUserCompany> memberships) {
        var context = AuthUtil.requireTrustedService();
        if (context.hasDelegate() && !Objects.equals(userId, AuthUtil.getLoginUserId())) {
            throw new InnerAccessDeniedException();
        }
        if (memberships.isEmpty()) {
            return List.of();
        }
        if (memberships.stream().anyMatch(membership -> membership == null
                || !Objects.equals(userId, membership.getUserId())
                || membership.getId() == null || membership.getCompanyId() == null
                || !InnerSystemAccess.active(membership.getDeleteFlag(), membership.getStatusFlag()))) {
            throw new InnerAccessDeniedException();
        }
        // 以当前数据库中的有效成员关系为准，不能由传入实体制造跨公司集合。
        List<SysUserCompany> activeMemberships = userCompanyMapper.selectList(
                new LambdaQueryWrapper<SysUserCompany>()
                        .eq(SysUserCompany::getUserId, userId)
                        .in(SysUserCompany::getId, memberships.stream().map(SysUserCompany::getId).toList())
                        .eq(SysUserCompany::getStatusFlag, 1)
                        .eq(SysUserCompany::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE));
        if (activeMemberships.isEmpty()) {
            return List.of();
        }
        return TenantQueryScope.withoutTenantLine(() -> doAssemble(userId, activeMemberships));
    }

    private List<RemoteUserContextDTO> doAssemble(String userId,
                                                  List<SysUserCompany> memberships) {
        // 1. 公司批量 + 有效性过滤
        List<String> companyIds = memberships.stream()
                .map(SysUserCompany::getCompanyId).distinct().toList();
        Map<String, SysCompany> companies = companyMapper.selectBatchIds(companyIds).stream()
                .filter(company -> InnerSystemAccess.active(company.getDeleteFlag(), company.getStatusFlag()))
                .collect(Collectors.toMap(SysCompany::getId, Function.identity()));

        // 2. 租户批量（一次查询）
        List<String> tenantIds = companies.values().stream()
                .map(SysCompany::getTenantId)
                .filter(id -> id != null && !id.isBlank())
                .distinct().toList();
        Map<String, SysTenant> tenants = tenantIds.isEmpty() ? Map.of()
                : tenantMapper.selectBatchIds(tenantIds).stream()
                .filter(tenant -> InnerSystemAccess.active(tenant.getDeleteFlag(), tenant.getStatusFlag()))
                .collect(Collectors.toMap(SysTenant::getId, Function.identity()));

        Set<String> availableCompanyIds = companies.values().stream()
                .filter(company -> tenants.containsKey(company.getTenantId()))
                .map(SysCompany::getId).collect(Collectors.toSet());
        if (availableCompanyIds.isEmpty()) {
            return List.of();
        }

        // 3. 角色绑定（一次）+ 角色实体（一次），按公司分组
        List<SysUserRole> bindings = userRoleMapper.selectList(
                        new LambdaQueryWrapper<SysUserRole>()
                                .eq(SysUserRole::getUserId, userId)
                                .in(SysUserRole::getCompanyId, availableCompanyIds)
                                .eq(SysUserRole::getDeleteFlag,
                                        RobotConstants.DELETE_FLAG_ACTIVE));
        Map<String, Set<String>> boundIdsByCompany = bindings.stream().collect(Collectors.groupingBy(
                SysUserRole::getCompanyId, Collectors.mapping(SysUserRole::getRoleId, Collectors.toSet())));
        Set<String> boundRoleIds = bindings.stream().map(SysUserRole::getRoleId).collect(Collectors.toSet());
        Map<String, List<SysRole>> rolesByCompany = boundRoleIds.isEmpty() ? Map.of()
                : roleMapper.selectList(new LambdaQueryWrapper<SysRole>()
                        .in(SysRole::getId, boundRoleIds)
                        .in(SysRole::getCompanyId, availableCompanyIds)
                        .eq(SysRole::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE)
                        .eq(SysRole::getStatusFlag, 1)).stream()
                .filter(role -> boundIdsByCompany.getOrDefault(role.getCompanyId(), Set.of())
                        .contains(role.getId()))
                .collect(Collectors.groupingBy(SysRole::getCompanyId));

        // 4. 拼装
        return memberships.stream()
                .map(membership -> {
                    SysCompany company = companies.get(membership.getCompanyId());
                    if (company == null || !availableCompanyIds.contains(company.getId())) {
                        return null;
                    }
                    SysTenant tenant = company.getTenantId() == null
                            ? null : tenants.get(company.getTenantId());
                    return RemoteUserContextDTO.builder()
                            .contextId(membership.getId())
                            .securityVersion(membership.getSecurityVersion())
                            .primaryFlag(membership.getPrimaryFlag() != null
                                    && membership.getPrimaryFlag() == 1)
                            .tenantId(company.getTenantId())
                            .tenantName(tenant == null ? null : tenant.getTenantName())
                            .companyId(membership.getCompanyId())
                            .companyName(company.getCompanyName())
                            .roles(rolesByCompany
                                    .getOrDefault(membership.getCompanyId(), List.of()).stream()
                                    .map(role -> RemoteUserContextDTO.RoleItem.builder()
                                            .id(role.getId())
                                            .code(role.getRoleCode())
                                            .name(role.getRoleName())
                                            .build())
                                    .toList())
                            .build();
                })
                .filter(context -> context != null)
                .toList();
    }
}
