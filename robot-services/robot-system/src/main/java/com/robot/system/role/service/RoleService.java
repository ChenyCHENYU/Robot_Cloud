package com.robot.system.role.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.robot.common.core.constant.RobotConstants;
import com.robot.system.api.constant.SystemConstants;
import com.robot.common.core.domain.PageResult;
import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceAssert;
import com.robot.common.core.exception.ServiceException;
import com.robot.system.api.dto.role.RolePageQuery;
import com.robot.system.api.dto.role.RoleSaveRequest;
import com.robot.system.api.dto.role.TempAuthorizationCreateRequest;
import com.robot.system.api.entity.SysRole;
import com.robot.system.api.entity.SysRoleMenu;
import com.robot.system.api.entity.SysTempAuthorization;
import com.robot.system.api.entity.SysUser;
import com.robot.system.api.entity.SysUserRole;
import com.robot.system.api.vo.role.RoleVO;

import com.robot.system.role.mapper.SysRoleMapper;
import com.robot.system.role.mapper.SysRoleMenuMapper;
import com.robot.system.role.mapper.SysRoleButtonMapper;
import com.robot.system.role.mapper.SysTempAuthorizationMapper;
import com.robot.system.api.entity.SysRoleButton;
import com.robot.system.api.entity.SysMenuButton;
import com.robot.system.menu.mapper.SysMenuButtonMapper;
import com.robot.system.menu.mapper.SysMenuMapper;
import com.robot.system.api.entity.SysMenu;
import com.robot.common.security.util.AuthUtil;
import com.robot.system.user.mapper.SysUserMapper;
import com.robot.system.user.mapper.SysUserRoleMapper;
import com.robot.system.permission.service.EffectiveRoleResolver;
import com.robot.system.permission.service.PermissionChangeRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import com.robot.system.api.vo.permission.DataScopeVO;
import com.robot.system.api.vo.role.RoleOptionVO;
import com.robot.system.api.vo.role.RoleUserVO;

import java.util.List;
import java.util.Map;

/**
 * 角色管理：公司内角色 CRUD + 菜单授权 + 数据范围 + 临时授权。
 * 权限变更统一落审计日志（sys_permission_audit_log 只追加）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoleService extends ServiceImpl<SysRoleMapper, SysRole> {

    private final SysRoleMenuMapper roleMenuMapper;
    private final SysRoleButtonMapper roleButtonMapper;
    private final SysMenuMapper menuMapper;
    private final SysMenuButtonMapper menuButtonMapper;
    private final EffectiveRoleResolver effectiveRoleResolver;
    private final SysUserRoleMapper userRoleMapper;
    private final SysTempAuthorizationMapper tempAuthorizationMapper;
    private final SysUserMapper userMapper;
    private final PermissionChangeRecorder changeRecorder;

    public PageResult<RoleVO> queryPage(RolePageQuery query) {
        LambdaQueryWrapper<SysRole> wrapper = new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE)
                .eq(query.getStatus() != null, SysRole::getStatusFlag, query.getStatus())
                .and(query.getKeyword() != null && !query.getKeyword().isBlank(), w -> w
                        .like(SysRole::getRoleName, query.getKeyword())
                        .or().like(SysRole::getRoleCode, query.getKeyword()))
                .orderByAsc(SysRole::getSortNo);
        Page<SysRole> page = page(new Page<>(query.pageValue(), query.pageSizeValue()), wrapper);
        List<RoleVO> records = page.getRecords().stream().map(RoleVO::from).toList();
        return PageResult.of(records, page.getTotal(), query.pageValue(), query.pageSizeValue());
    }

    /** 角色下拉选项（前端 /sys/roles/options）。 */
    public List<RoleOptionVO> options() {
        return list(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE)
                .eq(SysRole::getStatusFlag, 1)
                .orderByAsc(SysRole::getSortNo))
                .stream()
                .map(role -> new RoleOptionVO(role.getId(), role.getRoleName(),
                        role.getRoleCode()))
                .toList();
    }

    public RoleVO getDetail(String id) {
        SysRole role = ServiceAssert.notNull(getById(id), ResultCode.ROLE_NOT_FOUND);
        List<String> menuIds = roleMenuMapper.selectList(new LambdaQueryWrapper<SysRoleMenu>()
                        .eq(SysRoleMenu::getRoleId, id))
                .stream().map(SysRoleMenu::getMenuId).toList();
        RoleVO vo = RoleVO.from(role);
        vo.setMenuIds(menuIds);
        return vo;
    }

    @Transactional(rollbackFor = Exception.class)
    public String create(RoleSaveRequest request) {
        ServiceAssert.isTrue(count(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getRoleCode, request.getRoleCode())
                .eq(SysRole::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE)) == 0,
                ResultCode.ROLE_CODE_DUPLICATED);
        SysRole role = new SysRole();
        applyFields(role, request);
        save(role);
        bindPermissions(role.getId(), request.effectivePermissionIds());
        changeRecorder.record("save_role", role.getId(), request);
        return role.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(String id, RoleSaveRequest request) {
        SysRole role = ServiceAssert.notNull(getById(id), ResultCode.ROLE_NOT_FOUND);
        ServiceAssert.isTrue(count(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getRoleCode, request.getRoleCode())
                .eq(SysRole::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE)
                .ne(SysRole::getId, id)) == 0,
                ResultCode.ROLE_CODE_DUPLICATED);
        applyFields(role, request);
        updateById(role);
        assertNotSelfRoleEdit(id);
        deleteBindings(id);
        bindPermissions(id, request.effectivePermissionIds());
        changeRecorder.record("update_role", id, request);
    }

    /** 角色权限批量授权（前端 PUT /sys/roles/{id}/permissions {permissionIds}）。 */
    @Transactional(rollbackFor = Exception.class)
    public void assignPermissions(String roleId, List<String> permissionIds) {
        SysRole role = ServiceAssert.notNull(getById(roleId), ResultCode.ROLE_NOT_FOUND);
        assertNotSelfRoleEdit(roleId);
        deleteBindings(roleId);
        bindPermissions(roleId, permissionIds);
        changeRecorder.record("assign_permissions", roleId,
                Map.of("permissionCount", permissionIds == null ? 0 : permissionIds.size()));
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(String id) {
        SysRole role = ServiceAssert.notNull(getById(id), ResultCode.ROLE_NOT_FOUND);
        ServiceAssert.isTrue(!RobotConstants.SUPER_ADMIN_ROLE_CODE.equals(role.getRoleCode()),
                ResultCode.FORBIDDEN, "超级管理员角色禁止删除");
        long bound = userRoleMapper.selectCount(new LambdaQueryWrapper<SysUserRole>()
                .eq(SysUserRole::getRoleId, id));
        ServiceAssert.isTrue(bound == 0, ResultCode.DATA_REFERENCED, "角色已分配用户，禁止删除");
        // MP 逻辑删除（软删）
        removeById(id);
        deleteBindings(id);
        changeRecorder.record("delete_role", id, null);
    }

    /** 角色成员（前端 /sys/roles/{id}/users；用户一次批量查询防 N+1）。 */
    public List<RoleUserVO> roleUsers(String roleId) {
        ServiceAssert.notNull(getById(roleId), ResultCode.ROLE_NOT_FOUND);
        List<String> userIds = userRoleMapper.selectList(new LambdaQueryWrapper<SysUserRole>()
                        .eq(SysUserRole::getRoleId, roleId)
                        .eq(SysUserRole::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE))
                .stream().map(SysUserRole::getUserId).distinct().toList();
        if (userIds.isEmpty()) {
            return List.of();
        }
        return userMapper.selectBatchIds(userIds).stream()
                .map(user -> new RoleUserVO(user.getId(), user.getUsername(),
                        user.getNickname(),
                        user.getStatusFlag() != null && user.getStatusFlag() == 1
                                ? "active" : "disabled"))
                .toList();
    }

    /** 角色数据范围配置（前端 /sys/roles/{id}/data-scopes）。 */
    public List<DataScopeVO> dataScopes(String roleId) {
        SysRole role = ServiceAssert.notNull(getById(roleId), ResultCode.ROLE_NOT_FOUND);
        return List.of(new DataScopeVO("*", role.getDataScope() == null
                ? SystemConstants.DATA_SCOPE_SELF : role.getDataScope()));
    }

    /** 临时授权清单。 */
    public List<SysTempAuthorization> tempAuthorizations(String roleId) {
        ServiceAssert.notNull(getById(roleId), ResultCode.ROLE_NOT_FOUND);
        return tempAuthorizationMapper.selectList(new LambdaQueryWrapper<SysTempAuthorization>()
                .eq(SysTempAuthorization::getRoleId, roleId)
                .eq(SysTempAuthorization::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE)
                .orderByDesc(SysTempAuthorization::getCreateDateTime));
    }

    /** 全部临时授权（治理页；租户拦截器自动限定本公司）。 */
    public List<SysTempAuthorization> allTempAuthorizations() {
        return tempAuthorizationMapper.selectList(new LambdaQueryWrapper<SysTempAuthorization>()
                .eq(SysTempAuthorization::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE)
                .orderByDesc(SysTempAuthorization::getCreateDateTime));
    }

    /** 创建临时授权（敏感写：有效期上限 30 天 + 审计留痕）。 */
    @Transactional(rollbackFor = Exception.class)
    public String createTempAuthorization(TempAuthorizationCreateRequest request) {
        ServiceAssert.isTrue(request.getEffectFrom().isBefore(request.getEffectTo()),
                ResultCode.PARAM_INVALID, "生效结束时间必须晚于开始时间");
        ServiceAssert.isTrue(request.getEffectFrom().plusDays(30).isAfter(request.getEffectTo()),
                ResultCode.PARAM_INVALID, "临时授权最长 30 天");
        ServiceAssert.notNull(getById(request.getRoleId()), ResultCode.ROLE_NOT_FOUND);
        SysUser target = ServiceAssert.notNull(userMapper.selectById(request.getUserId()),
                ResultCode.USER_NOT_FOUND);

        SysTempAuthorization authorization = new SysTempAuthorization();
        authorization.setUserId(request.getUserId());
        authorization.setRoleId(request.getRoleId());
        authorization.setEffectFrom(request.getEffectFrom());
        authorization.setEffectTo(request.getEffectTo());
        authorization.setReason(request.getReason());
        authorization.setStatusFlag(1);
        tempAuthorizationMapper.insert(authorization);

        changeRecorder.record("temp_authorize", request.getUserId(), Map.of(
                "roleId", request.getRoleId(),
                "targetUsername", target.getUsername(),
                "effectTo", request.getEffectTo().toString()));
        return authorization.getId();
    }

    private void applyFields(SysRole role, RoleSaveRequest request) {
        role.setRoleName(request.getRoleName());
        role.setRoleCode(request.getRoleCode());
        role.setDataScope(request.getDataScope() == null
                ? SystemConstants.DATA_SCOPE_SELF : request.getDataScope());
        role.setSortNo(request.getSortNo() == null ? 0 : request.getSortNo());
        role.setStatusFlag(request.getStatus() == null ? 1 : request.getStatus());
        role.setRemark(request.getRemark());
    }

    /**
     * 权限绑定分流（RC11）：permissionIds 中菜单 ID 进 sys_role_menu、按钮 ID 进
     * sys_role_button；未知/重复/跨资源 ID 一律拒绝（防静默丢授权）。
     */
    private void bindPermissions(String roleId, List<String> permissionIds) {
        if (permissionIds == null || permissionIds.isEmpty()) {
            return;
        }
        List<String> distinctIds = permissionIds.stream().distinct().toList();
        assertWithinGrantCeiling(distinctIds);

        java.util.Set<String> resolvedMenus = new java.util.HashSet<>();
        for (SysMenu menu : menuMapper.selectBatchIds(distinctIds)) {
            if (menu.getDeleteFlag() != null && menu.getDeleteFlag() == 1) {
                resolvedMenus.add(menu.getId());
            }
        }
        java.util.Set<String> resolvedButtons = new java.util.HashSet<>();
        for (SysMenuButton button : menuButtonMapper.selectBatchIds(distinctIds)) {
            if (button.getDeleteFlag() != null && button.getDeleteFlag() == 1) {
                resolvedButtons.add(button.getId());
            }
        }
        ServiceAssert.isTrue(
                resolvedMenus.size() + resolvedButtons.size() == distinctIds.size(),
                ResultCode.PARAM_INVALID, "包含未知或已失效的权限资源 ID");

        for (String menuId : resolvedMenus) {
            SysRoleMenu binding = new SysRoleMenu();
            binding.setRoleId(roleId);
            binding.setMenuId(menuId);
            roleMenuMapper.insert(binding);
        }
        for (String buttonId : resolvedButtons) {
            SysRoleButton binding = new SysRoleButton();
            binding.setRoleId(roleId);
            binding.setButtonId(buttonId);
            roleButtonMapper.insert(binding);
        }
    }

    /** 清空角色全部权限绑定（菜单 + 按钮）。 */
    private void deleteBindings(String roleId) {
        roleMenuMapper.delete(new LambdaQueryWrapper<SysRoleMenu>()
                .eq(SysRoleMenu::getRoleId, roleId));
        roleButtonMapper.delete(new LambdaQueryWrapper<SysRoleButton>()
                .eq(SysRoleButton::getRoleId, roleId));
    }

    /**
     * 授予上界（RC11/T18）：非超管操作者只能授予自己有效集合内的权限。
     */
    private void assertWithinGrantCeiling(List<String> requestedIds) {
        String operatorId = AuthUtil.getLoginUserId();
        if (operatorId == null || AuthUtil.isSuperAdmin()) {
            return; // 系统任务或超管不受上界限制
        }
        com.robot.system.permission.service.EffectiveRoleResolver.EffectiveRoles effective =
                effectiveRoleResolver.resolve(operatorId, AuthUtil.getLoginCompanyId());
        java.util.Set<String> ownable = new java.util.HashSet<>();
        if (!effective.isEmpty()) {
            roleMenuMapper.selectList(new LambdaQueryWrapper<SysRoleMenu>()
                            .in(SysRoleMenu::getRoleId, effective.roleIds()))
                    .forEach(binding -> ownable.add(binding.getMenuId()));
            roleButtonMapper.selectList(new LambdaQueryWrapper<SysRoleButton>()
                            .in(SysRoleButton::getRoleId, effective.roleIds()))
                    .forEach(binding -> ownable.add(binding.getButtonId()));
        }
        for (String id : requestedIds) {
            ServiceAssert.isTrue(ownable.contains(id), ResultCode.FORBIDDEN,
                    "禁止授予超出自身权限范围的资源: " + id);
        }
    }

    /** 自授禁令：非超管不得修改自己持有角色的权限集。 */
    private void assertNotSelfRoleEdit(String roleId) {
        String operatorId = AuthUtil.getLoginUserId();
        if (operatorId == null || AuthUtil.isSuperAdmin()) {
            return;
        }
        com.robot.system.permission.service.EffectiveRoleResolver.EffectiveRoles effective =
                effectiveRoleResolver.resolve(operatorId, AuthUtil.getLoginCompanyId());
        if (effective.roleIds().contains(roleId)) {
            throw new ServiceException(ResultCode.FORBIDDEN,
                    "禁止修改自己持有角色的权限（自授禁令）");
        }
    }



    /** 当前有效临时授权（过期视为失效，由登录链路按需并入角色）。 */
    public List<SysTempAuthorization> activeTempAuthorizations(String userId, String companyId) {
        LocalDateTime now = LocalDateTime.now();
        return tempAuthorizationMapper.selectList(new LambdaQueryWrapper<SysTempAuthorization>()
                .eq(SysTempAuthorization::getUserId, userId)
                .eq(SysTempAuthorization::getStatusFlag, 1)
                .eq(SysTempAuthorization::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE)
                .le(SysTempAuthorization::getEffectFrom, now)
                .ge(SysTempAuthorization::getEffectTo, now));
    }
}
