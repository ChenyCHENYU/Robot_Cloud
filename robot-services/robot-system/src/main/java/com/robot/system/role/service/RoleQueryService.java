package com.robot.system.role.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.robot.common.core.constant.RobotConstants;
import com.robot.common.core.domain.PageQuery;
import com.robot.common.core.domain.PageResult;
import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceAssert;
import com.robot.system.api.dto.role.RolePageQuery;
import com.robot.system.api.dto.role.TemporaryAuthorizationPageQuery;
import com.robot.system.api.entity.SysRole;
import com.robot.system.api.entity.SysRoleButton;
import com.robot.system.api.entity.SysRoleMenu;
import com.robot.system.api.entity.SysTempAuthorization;
import com.robot.system.api.entity.SysUser;
import com.robot.system.api.entity.SysUserCompany;
import com.robot.system.api.vo.permission.DataScopeVO;
import com.robot.system.api.vo.role.RoleOptionVO;
import com.robot.system.api.vo.role.RoleUserVO;
import com.robot.system.api.vo.role.RoleVO;
import com.robot.system.api.vo.role.TemporaryAuthorizationVO;
import com.robot.system.permission.service.PermissionGrantPolicy;
import com.robot.system.role.mapper.SysRoleButtonMapper;
import com.robot.system.role.mapper.SysRoleMapper;
import com.robot.system.role.mapper.SysRoleMenuMapper;
import com.robot.system.role.mapper.SysTempAuthorizationMapper;
import com.robot.system.user.mapper.SysUserCompanyMapper;
import com.robot.system.user.mapper.SysUserMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 当前公司角色的查询用例；列表、资源绑定和治理视图不承载写事务。
 *
 * @author Robot Cloud
 * @since 1.0.0-SNAPSHOT
 */
@Service
@RequiredArgsConstructor
public class RoleQueryService {
    private static final int LEGACY_QUERY_LIMIT = 200;

    private final SysRoleMapper roleMapper;
    private final SysRoleMenuMapper roleMenuMapper;
    private final SysRoleButtonMapper roleButtonMapper;
    private final PermissionGrantPolicy grantPolicy;
    private final SysTempAuthorizationMapper tempAuthorizationMapper;
    private final SysUserMapper userMapper;
    private final SysUserCompanyMapper membershipMapper;
    private final Clock clock;

    /**
     * 查询当前公司未删除角色；分页统计与结果使用相同谓词。
     *
     * @param query 分页与筛选条件
     * @return 当前事实派生的结果
     */
    public PageResult<RoleVO> queryPage(RolePageQuery query) {
        String companyId = grantPolicy.requireCompany();
        LambdaQueryWrapper<SysRole> wrapper =
                new LambdaQueryWrapper<SysRole>()
                        .eq(SysRole::getCompanyId, companyId)
                        .eq(SysRole::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE)
                        .eq(query.getStatus() != null, SysRole::getStatusFlag, query.getStatus())
                        .and(
                                query.getKeyword() != null && !query.getKeyword().isBlank(),
                                w ->
                                        w.like(SysRole::getRoleName, query.getKeyword())
                                                .or()
                                                .like(SysRole::getRoleCode, query.getKeyword()))
                        .orderByAsc(SysRole::getSortNo, SysRole::getId);
        Page<SysRole> page =
                roleMapper.selectPage(
                        new Page<>(query.pageValue(), query.pageSizeValue()), wrapper);
        return PageResult.of(
                page.getRecords().stream().map(RoleVO::from).toList(),
                page.getTotal(),
                query.pageValue(),
                query.pageSizeValue());
    }

    /**
     * 仅提供当前公司启用角色选项，选择后仍须经过写入授权上界校验。
     *
     * @return 当前事实派生的结果
     */
    public List<RoleOptionVO> options() {
        return roleMapper
                .selectList(
                        new LambdaQueryWrapper<SysRole>()
                                .eq(SysRole::getCompanyId, grantPolicy.requireCompany())
                                .eq(SysRole::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE)
                                .eq(SysRole::getStatusFlag, 1)
                                .orderByAsc(SysRole::getSortNo, SysRole::getId))
                .stream()
                .map(role -> new RoleOptionVO(role.getId(), role.getRoleName(), role.getRoleCode()))
                .toList();
    }

    /**
     * 返回当前公司角色版本与菜单、按钮精确绑定，供编辑命令携带版本。
     *
     * @param id 记录ID
     * @return 角色版本及菜单、按钮精确绑定详情
     */
    public RoleVO getDetail(String id) {
        SysRole role = requireRole(id, grantPolicy.requireCompany());
        List<String> menuIds =
                roleMenuMapper
                        .selectList(
                                new LambdaQueryWrapper<SysRoleMenu>()
                                        .eq(SysRoleMenu::getRoleId, id)
                                        .eq(SysRoleMenu::getDeleteFlag, 1))
                        .stream()
                        .map(SysRoleMenu::getMenuId)
                        .toList();
        List<String> buttonIds =
                roleButtonMapper
                        .selectList(
                                new LambdaQueryWrapper<SysRoleButton>()
                                        .eq(SysRoleButton::getRoleId, id)
                                        .eq(SysRoleButton::getDeleteFlag, 1))
                        .stream()
                        .map(SysRoleButton::getButtonId)
                        .toList();
        RoleVO vo = RoleVO.from(role);
        vo.setMenuIds(menuIds);
        vo.setButtonIds(buttonIds);
        vo.setPermissionIds(
                java.util.stream.Stream.concat(menuIds.stream(), buttonIds.stream())
                        .distinct()
                        .toList());
        return vo;
    }

    /**
     * 兼容旧清单接口，最多200条；完整集合使用成员分页。
     *
     * @param roleId 公司角色ID
     * @return 当前事实派生的结果
     */
    public List<RoleUserVO> roleUsers(String roleId) {
        PageQuery query = new PageQuery();
        query.setPageSize(LEGACY_QUERY_LIMIT);
        return roleUsersPage(roleId, query).getList();
    }

    /**
     * 在SQL中直接限定公司成员与角色绑定，避免先加载全公司和无界IN。
     *
     * @param roleId 公司角色ID
     * @param query 分页与筛选条件
     * @return 当前事实派生的结果
     */
    public PageResult<RoleUserVO> roleUsersPage(String roleId, PageQuery query) {
        String companyId = grantPolicy.requireCompany();
        requireRole(roleId, companyId);
        Page<SysUser> page =
                userMapper.selectPage(
                        new Page<>(query.pageValue(), query.pageSizeValue()),
                        new LambdaQueryWrapper<SysUser>()
                                .exists(
                                        "SELECT 1 FROM sys_user_role ur WHERE ur.user_id ="
                                                + " sys_user.id AND ur.company_id = {0} AND ur.role_id"
                                                + " = {1} AND ur.delete_flag = 1",
                                        companyId, roleId)
                                .exists(
                                        "SELECT 1 FROM sys_user_company m WHERE m.user_id ="
                                                + " sys_user.id AND m.company_id = {0} AND"
                                                + " m.delete_flag = 1",
                                        companyId)
                                .orderByAsc(SysUser::getId));
        List<String> ids = page.getRecords().stream().map(SysUser::getId).toList();
        Map<String, SysUserCompany> members =
                ids.isEmpty()
                        ? Map.of()
                        : membershipMapper
                                .selectList(
                                        new LambdaQueryWrapper<SysUserCompany>()
                                                .eq(SysUserCompany::getCompanyId, companyId)
                                                .in(SysUserCompany::getUserId, ids)
                                                .eq(SysUserCompany::getDeleteFlag, 1))
                                .stream()
                                .collect(
                                        Collectors.toMap(
                                                SysUserCompany::getUserId, Function.identity()));
        List<RoleUserVO> records =
                page.getRecords().stream()
                        .filter(user -> members.containsKey(user.getId()))
                        .map(
                                user ->
                                        new RoleUserVO(
                                                user.getId(),
                                                user.getUsername(),
                                                user.getNickname(),
                                                Integer.valueOf(1).equals(user.getStatusFlag())
                                                                && Integer.valueOf(1)
                                                                        .equals(
                                                                                members.get(
                                                                                                user
                                                                                                        .getId())
                                                                                        .getStatusFlag())
                                                        ? "active"
                                                        : "disabled"))
                        .toList();
        return PageResult.of(records, page.getTotal(), query.pageValue(), query.pageSizeValue());
    }

    /**
     * 展示角色配置范围；该返回值本身不执行业务数据权限过滤。
     *
     * @param roleId 公司角色ID
     * @return 当前事实派生的结果
     */
    public List<DataScopeVO> dataScopes(String roleId) {
        SysRole role = requireRole(roleId, grantPolicy.requireCompany());
        return List.of(
                new DataScopeVO("*", role.getDataScope() == null ? "self" : role.getDataScope()));
    }

    /**
     * 兼容清单有界返回；历史查询使用分页端点。
     *
     * @param roleId 公司角色ID
     * @return 当前事实派生的结果
     */
    public List<TemporaryAuthorizationVO> tempAuthorizations(String roleId) {
        requireRole(roleId, grantPolicy.requireCompany());
        TemporaryAuthorizationPageQuery query = new TemporaryAuthorizationPageQuery();
        query.setRoleId(roleId);
        query.setPageSize(LEGACY_QUERY_LIMIT);
        return temporaryAuthorizationPage(query).getList();
    }

    /**
     * 治理旧入口只返回当前公司最近200项；完整检索应使用分页入口。
     *
     * @return 当前事实派生的结果
     */
    public List<TemporaryAuthorizationVO> allTempAuthorizations() {
        TemporaryAuthorizationPageQuery query = new TemporaryAuthorizationPageQuery();
        query.setPageSize(LEGACY_QUERY_LIMIT);
        return temporaryAuthorizationPage(query).getList();
    }

    /**
     * 公司谓词、过滤和稳定分页在数据库执行，用户/角色名称一次批量装配。
     *
     * @param query 分页与筛选条件
     * @return 当前事实派生的结果
     */
    public PageResult<TemporaryAuthorizationVO> temporaryAuthorizationPage(
            TemporaryAuthorizationPageQuery query) {
        String company = grantPolicy.requireCompany();
        Page<SysTempAuthorization> page =
                tempAuthorizationMapper.selectPage(
                        new Page<>(query.pageValue(), query.pageSizeValue()),
                        new LambdaQueryWrapper<SysTempAuthorization>()
                                .eq(SysTempAuthorization::getCompanyId, company)
                                .eq(SysTempAuthorization::getDeleteFlag, 1)
                                .eq(
                                        query.getUserId() != null,
                                        SysTempAuthorization::getUserId,
                                        query.getUserId())
                                .eq(
                                        query.getRoleId() != null,
                                        SysTempAuthorization::getRoleId,
                                        query.getRoleId())
                                .eq(
                                        query.getStatusFlag() != null,
                                        SysTempAuthorization::getStatusFlag,
                                        query.getStatusFlag())
                                .orderByDesc(
                                        SysTempAuthorization::getCreateDateTime,
                                        SysTempAuthorization::getId));
        List<SysTempAuthorization> entries = page.getRecords();
        Map<String, SysUser> users =
                entries.isEmpty()
                        ? Map.of()
                        : userMapper
                                .selectBatchIds(
                                        entries.stream()
                                                .map(SysTempAuthorization::getUserId)
                                                .distinct()
                                                .toList())
                                .stream()
                                .collect(Collectors.toMap(SysUser::getId, Function.identity()));
        Map<String, SysRole> roles =
                entries.isEmpty()
                        ? Map.of()
                        : roleMapper
                                .selectBatchIds(
                                        entries.stream()
                                                .map(SysTempAuthorization::getRoleId)
                                                .distinct()
                                                .toList())
                                .stream()
                                .filter(role -> company.equals(role.getCompanyId()))
                                .collect(Collectors.toMap(SysRole::getId, Function.identity()));
        List<TemporaryAuthorizationVO> records =
                entries.stream()
                        .map(
                                entry ->
                                        TemporaryAuthorizationVO.builder()
                                                .id(entry.getId())
                                                .userId(entry.getUserId())
                                                .roleId(entry.getRoleId())
                                                .userNickname(
                                                        users.containsKey(entry.getUserId())
                                                                ? users.get(entry.getUserId())
                                                                        .getNickname()
                                                                : null)
                                                .roleName(
                                                        roles.containsKey(entry.getRoleId())
                                                                ? roles.get(entry.getRoleId())
                                                                        .getRoleName()
                                                                : null)
                                                .effectFrom(entry.getEffectFrom())
                                                .effectTo(entry.getEffectTo())
                                                .reason(entry.getReason())
                                                .statusFlag(entry.getStatusFlag())
                                                .revision(entry.getRevision())
                                                .build())
                        .toList();
        return PageResult.of(records, page.getTotal(), query.pageValue(), query.pageSizeValue());
    }

    /**
     * 仅读取指定公司用户在当前半开时间窗内生效的临时角色授权。
     *
     * @param userId 平台账号ID
     * @param companyId 服务端确认的公司ID
     * @return 当前事实派生的结果
     */
    public List<SysTempAuthorization> activeTempAuthorizations(String userId, String companyId) {
        ServiceAssert.isTrue(grantPolicy.requireCompany().equals(companyId), ResultCode.FORBIDDEN);
        LocalDateTime now = LocalDateTime.now(clock);
        return tempAuthorizationMapper.selectList(
                new LambdaQueryWrapper<SysTempAuthorization>()
                        .eq(SysTempAuthorization::getCompanyId, companyId)
                        .eq(SysTempAuthorization::getUserId, userId)
                        .eq(SysTempAuthorization::getStatusFlag, 1)
                        .eq(SysTempAuthorization::getDeleteFlag, 1)
                        .le(SysTempAuthorization::getEffectFrom, now)
                        .gt(SysTempAuthorization::getEffectTo, now));
    }

    private SysRole requireRole(String id, String companyId) {
        ServiceAssert.notBlank(id, ResultCode.PARAM_INVALID, "角色ID不能为空");
        SysRole role = ServiceAssert.notNull(roleMapper.selectById(id), ResultCode.ROLE_NOT_FOUND);
        ServiceAssert.isTrue(
                companyId.equals(role.getCompanyId())
                        && Integer.valueOf(1).equals(role.getDeleteFlag()),
                ResultCode.FORBIDDEN,
                "角色不属于当前公司");
        return role;
    }
}
