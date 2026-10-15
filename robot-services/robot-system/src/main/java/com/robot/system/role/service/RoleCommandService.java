package com.robot.system.role.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.robot.common.core.constant.RobotConstants;
import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceAssert;
import com.robot.common.security.util.AuthUtil;
import com.robot.system.api.dto.role.RoleSaveRequest;
import com.robot.system.api.entity.SysMenu;
import com.robot.system.api.entity.SysMenuButton;
import com.robot.system.api.entity.SysRole;
import com.robot.system.api.entity.SysRoleButton;
import com.robot.system.api.entity.SysRoleMenu;
import com.robot.system.api.entity.SysTempAuthorization;
import com.robot.system.api.entity.SysUserRole;
import com.robot.system.menu.mapper.SysMenuButtonMapper;
import com.robot.system.menu.mapper.SysMenuMapper;
import com.robot.system.permission.service.PermissionChangeRecorder;
import com.robot.system.permission.service.PermissionGrantPolicy;
import com.robot.system.permission.service.PermissionGrantPolicy.Operator;
import com.robot.system.permission.service.PermissionStateStore;
import com.robot.system.role.mapper.SysRoleButtonMapper;
import com.robot.system.role.mapper.SysRoleMapper;
import com.robot.system.role.mapper.SysRoleMenuMapper;
import com.robot.system.role.mapper.SysTempAuthorizationMapper;
import com.robot.system.user.mapper.SysUserRoleMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 公司角色写用例；主角色、资源绑定、审计与缓存事件保留完整事务边界。
 *
 * @author Robot Cloud
 * @since 1.0.0-SNAPSHOT
 */
@Service
@RequiredArgsConstructor
public class RoleCommandService extends ServiceImpl<SysRoleMapper, SysRole> {
    private static final int MAX_ROLE_NAME_LENGTH = 30;
    private static final int MAX_ROLE_CODE_LENGTH = 50;
    private static final int MAX_REMARK_LENGTH = 255;
    private static final int MAX_RESOURCE_ID_LENGTH = 64;

    private final SysRoleMenuMapper roleMenuMapper;
    private final SysRoleButtonMapper roleButtonMapper;
    private final SysMenuMapper menuMapper;
    private final SysMenuButtonMapper menuButtonMapper;
    private final PermissionGrantPolicy grantPolicy;
    private final SysUserRoleMapper userRoleMapper;
    private final SysTempAuthorizationMapper tempAuthorizationMapper;
    private final PermissionChangeRecorder changeRecorder;
    private final Clock clock;
    private final PermissionStateStore stateStore;

    /**
     * 创建公司角色；永久授权上界先于角色、资源关联和审计写入校验。
     *
     * @param request 该用例接收的白名单请求
     * @return 当前事实派生的结果
     */
    @Transactional(rollbackFor = Exception.class)
    public String create(RoleSaveRequest request) {
        stateStore.lockMenuTree();
        stateStore.lockCompanyWrites(AuthUtil.getLoginCompanyId());
        Operator operator = grantPolicy.requireOperator();
        validateFields(request);
        grantPolicy.assertRoleCode(operator, null, request.getRoleCode());
        String scope = request.getDataScope() == null ? "self" : request.getDataScope();
        grantPolicy.assertScope(operator, scope);
        assertCodeUnique(null, request.getRoleCode(), operator.companyId());
        PermissionBindings bindings =
                validatePermissions(operator, request.effectivePermissionIds());
        SysRole role = new SysRole();
        role.setCompanyId(operator.companyId());
        applyFields(role, request);
        ServiceAssert.isTrue(save(role), ResultCode.DATA_CONCURRENT_CONFLICT);
        insertBindings(role.getId(), bindings);
        changeRecorder.record("save_role", role.getId(), request);
        return role.getId();
    }

    /**
     * 修改角色聚合；省略权限字段保留原绑定，revision冲突时不重绑或记录成功。
     *
     * @param id 记录ID
     * @param request 该用例接收的白名单请求
     */
    @Transactional(rollbackFor = Exception.class)
    public void update(String id, RoleSaveRequest request) {
        stateStore.lockMenuTree();
        stateStore.lockCompanyWrites(AuthUtil.getLoginCompanyId());
        Operator operator = grantPolicy.requireOperator();
        SysRole role = requireRole(id, operator.companyId());
        grantPolicy.assertNotSelfRoleEdit(operator, id);
        grantPolicy.assertManagedRoles(operator, List.of(role));
        validateFields(request);
        grantPolicy.assertRoleCode(operator, role, request.getRoleCode());
        if (request.getDataScope() != null) {
            grantPolicy.assertScope(operator, request.getDataScope());
        }
        ServiceAssert.isTrue(
                !RobotConstants.SUPER_ADMIN_ROLE_CODE.equals(role.getRoleCode())
                        || request.getStatus() == null
                        || request.getStatus() == 1,
                ResultCode.FORBIDDEN,
                "超级管理员角色禁止停用");
        assertRevision(role.getRevision(), request.getRevision());
        assertCodeUnique(id, request.getRoleCode(), operator.companyId());
        PermissionBindings bindings =
                request.effectivePermissionIds() == null
                        ? null
                        : validatePermissions(operator, request.effectivePermissionIds());
        applyFields(role, request);
        ServiceAssert.isTrue(updateById(role), ResultCode.DATA_CONCURRENT_CONFLICT);
        if (bindings != null) {
            deleteBindings(id);
            insertBindings(id, bindings);
        }
        changeRecorder.record("update_role", id, request);
    }

    /**
     * 单独权限命令也用角色revision锁住关联聚合；调用者应传详情中的revision。
     *
     * @param roleId 公司角色ID
     * @param ids 待校验的精确资源ID集合
     * @param revision 当前聚合版本
     */
    @Transactional(rollbackFor = Exception.class)
    public void assignPermissions(String roleId, List<String> ids, Integer revision) {
        stateStore.lockMenuTree();
        stateStore.lockCompanyWrites(AuthUtil.getLoginCompanyId());
        Operator operator = grantPolicy.requireOperator();
        SysRole role = requireRole(roleId, operator.companyId());
        grantPolicy.assertNotSelfRoleEdit(operator, roleId);
        grantPolicy.assertManagedRoles(operator, List.of(role));
        ServiceAssert.notNull(ids, ResultCode.PARAM_INVALID, "permissionIds不能为空；空数组表示清空");
        assertRevision(role.getRevision(), revision);
        PermissionBindings bindings = validatePermissions(operator, ids);
        ServiceAssert.isTrue(updateById(role), ResultCode.DATA_CONCURRENT_CONFLICT);
        deleteBindings(roleId);
        insertBindings(roleId, bindings);
        changeRecorder.record(
                "assign_permissions", roleId, Map.of("permissionCount", bindings.size()));
    }

    /**
     * 按客户端角色版本删除无绑定的公司角色，关联删除、审计和缓存事件同事务。
     *
     * @param id 记录ID
     * @param expectedRevision 客户端期望的聚合版本
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(String id, Integer expectedRevision) {
        stateStore.lockMenuTree();
        stateStore.lockCompanyWrites(AuthUtil.getLoginCompanyId());
        Operator operator = grantPolicy.requireOperator();
        SysRole role = requireRole(id, operator.companyId());
        grantPolicy.assertNotSelfRoleEdit(operator, id);
        grantPolicy.assertManagedRoles(operator, List.of(role));
        ServiceAssert.isTrue(
                !RobotConstants.SUPER_ADMIN_ROLE_CODE.equals(role.getRoleCode()),
                ResultCode.FORBIDDEN,
                "超级管理员角色禁止删除");
        assertRevision(role.getRevision(), expectedRevision);
        long bound =
                userRoleMapper.selectCount(
                        new LambdaQueryWrapper<SysUserRole>()
                                .eq(SysUserRole::getCompanyId, operator.companyId())
                                .eq(SysUserRole::getRoleId, id)
                                .eq(SysUserRole::getDeleteFlag, 1));
        long temporary =
                tempAuthorizationMapper.selectCount(
                        new LambdaQueryWrapper<SysTempAuthorization>()
                                .eq(SysTempAuthorization::getCompanyId, operator.companyId())
                                .eq(SysTempAuthorization::getRoleId, id)
                                .eq(SysTempAuthorization::getDeleteFlag, 1)
                                .eq(SysTempAuthorization::getStatusFlag, 1)
                                .gt(SysTempAuthorization::getEffectTo, LocalDateTime.now(clock)));
        ServiceAssert.isTrue(
                bound == 0 && temporary == 0, ResultCode.DATA_REFERENCED, "角色仍有用户绑定或未结束的临时授权");
        ServiceAssert.isTrue(
                remove(
                        new LambdaQueryWrapper<SysRole>()
                                .eq(SysRole::getId, id)
                                .eq(SysRole::getCompanyId, operator.companyId())
                                .eq(SysRole::getRevision, expectedRevision)),
                ResultCode.DATA_CONCURRENT_CONFLICT);
        deleteBindings(id);
        changeRecorder.record("delete_role", id, null);
    }

    private SysRole requireRole(String id, String companyId) {
        ServiceAssert.notBlank(id, ResultCode.PARAM_INVALID, "角色ID不能为空");
        SysRole role = ServiceAssert.notNull(getById(id), ResultCode.ROLE_NOT_FOUND);
        ServiceAssert.isTrue(
                companyId.equals(role.getCompanyId())
                        && Integer.valueOf(1).equals(role.getDeleteFlag()),
                ResultCode.FORBIDDEN,
                "角色不属于当前公司");
        return role;
    }

    private void assertCodeUnique(String id, String code, String companyId) {
        ServiceAssert.isTrue(
                count(
                                new LambdaQueryWrapper<SysRole>()
                                        .eq(SysRole::getCompanyId, companyId)
                                        .eq(SysRole::getRoleCode, code)
                                        .eq(SysRole::getDeleteFlag, 1)
                                        .ne(id != null, SysRole::getId, id))
                        == 0,
                ResultCode.ROLE_CODE_DUPLICATED);
    }

    private static void assertRevision(Integer actual, Integer expected) {
        ServiceAssert.notNull(expected, ResultCode.PARAM_INVALID, "命令必须提供版本号");
        ServiceAssert.isTrue(expected >= 0, ResultCode.PARAM_INVALID, "版本号不能小于0");
        ServiceAssert.isTrue(expected.equals(actual), ResultCode.DATA_CONCURRENT_CONFLICT);
    }

    private static void validateFields(RoleSaveRequest request) {
        ServiceAssert.isTrue(
                request.isPermissionIdsConsistent(), ResultCode.PARAM_INVALID, "权限字段不能重复提交");
        ServiceAssert.notBlank(request.getRoleName(), ResultCode.PARAM_INVALID);
        ServiceAssert.notBlank(request.getRoleCode(), ResultCode.PARAM_INVALID);
        ServiceAssert.isTrue(
                request.getRoleCode().matches("^[A-Z][A-Z0-9_]{1,49}$"),
                ResultCode.PARAM_INVALID,
                "角色编码为大写字母开头的字母数字下划线");
        ServiceAssert.isTrue(
                request.getStatus() == null || request.getStatus() == 0 || request.getStatus() == 1,
                ResultCode.PARAM_INVALID,
                "状态只允许0/1");
        ServiceAssert.isTrue(
                request.getRoleName().length() <= MAX_ROLE_NAME_LENGTH
                        && request.getRoleCode().length() <= MAX_ROLE_CODE_LENGTH
                        && (request.getRemark() == null
                                || request.getRemark().length() <= MAX_REMARK_LENGTH),
                ResultCode.PARAM_INVALID,
                "角色字段超长");
    }

    private void applyFields(SysRole role, RoleSaveRequest request) {
        role.setRoleName(request.getRoleName());
        role.setRoleCode(request.getRoleCode());
        applyScope(role, request);
        applyOrdering(role, request);
        role.setRemark(request.getRemark());
    }

    private void applyScope(SysRole role, RoleSaveRequest request) {
        if (request.getDataScope() != null || role.getDataScope() == null) {
            role.setDataScope(request.getDataScope() == null ? "self" : request.getDataScope());
        }
    }

    private void applyOrdering(SysRole role, RoleSaveRequest request) {
        if (request.getSortNo() != null || role.getSortNo() == null) {
            role.setSortNo(request.getSortNo() == null ? Integer.valueOf(0) : request.getSortNo());
        }
        if (request.getStatus() != null || role.getStatusFlag() == null) {
            role.setStatusFlag(
                    request.getStatus() == null ? Integer.valueOf(1) : request.getStatus());
        }
    }

    private record PermissionBindings(Set<String> menus, Set<String> buttons) {
        int size() {
            return menus.size() + buttons.size();
        }
    }

    /** 先校验授权上界，再将真实且有效的资源ID拆为页面和按钮绑定。 */
    private PermissionBindings validatePermissions(Operator operator, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return new PermissionBindings(Set.of(), Set.of());
        }
        validatePermissionIds(ids);
        grantPolicy.assertPermissions(operator, Set.copyOf(ids));
        Set<String> menus = activeRequestedMenuIds(ids);
        Set<String> buttonIds = activeRequestedButtonIds(ids);
        ServiceAssert.isTrue(
                java.util.Collections.disjoint(menus, buttonIds)
                        && menus.size() + buttonIds.size() == ids.size(),
                ResultCode.PARAM_INVALID,
                "包含未知、停用、父菜单失效或类型歧义的权限资源ID");
        return new PermissionBindings(menus, buttonIds);
    }

    private static void validatePermissionIds(List<String> ids) {
        ServiceAssert.isTrue(
                ids.size() <= RobotConstants.BATCH_WRITE_LIMIT
                        && ids.stream()
                                .allMatch(
                                        id ->
                                                id != null
                                                        && !id.isBlank()
                                                        && id.length() <= MAX_RESOURCE_ID_LENGTH)
                        && new HashSet<>(ids).size() == ids.size(),
                ResultCode.PARAM_INVALID,
                "权限资源ID必须有效、不重复且数量在单批上限内");
    }

    private Set<String> activeRequestedMenuIds(List<String> ids) {
        return menuMapper.selectBatchIds(ids).stream()
                .filter(
                        menu ->
                                ids.contains(menu.getId())
                                        && Integer.valueOf(1).equals(menu.getDeleteFlag())
                                        && Integer.valueOf(1).equals(menu.getStatusFlag()))
                .map(SysMenu::getId)
                .collect(Collectors.toSet());
    }

    private Set<String> activeRequestedButtonIds(List<String> ids) {
        List<SysMenuButton> buttons =
                menuButtonMapper.selectBatchIds(ids).stream()
                        .filter(
                                button ->
                                        ids.contains(button.getId())
                                                && Integer.valueOf(1).equals(button.getDeleteFlag())
                                                && Integer.valueOf(1)
                                                        .equals(button.getStatusFlag()))
                        .toList();
        Set<String> parents =
                buttons.stream().map(SysMenuButton::getMenuId).collect(Collectors.toSet());
        Set<String> liveParents =
                parents.isEmpty()
                        ? Set.of()
                        : menuMapper.selectBatchIds(parents).stream()
                                .filter(
                                        menu ->
                                                Integer.valueOf(1).equals(menu.getDeleteFlag())
                                                        && Integer.valueOf(1)
                                                                .equals(menu.getStatusFlag()))
                                .map(SysMenu::getId)
                                .collect(Collectors.toSet());
        return buttons.stream()
                .filter(button -> liveParents.contains(button.getMenuId()))
                .map(SysMenuButton::getId)
                .collect(Collectors.toSet());
    }

    // 各资源绑定持有独立ID和MP插入填充状态；批次有界且外层事务整体回滚。
    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
    private void insertBindings(String roleId, PermissionBindings bindings) {
        for (String id : bindings.menus()) {
            SysRoleMenu binding = new SysRoleMenu();
            binding.setRoleId(roleId);
            binding.setMenuId(id);
            ServiceAssert.isTrue(
                    roleMenuMapper.insert(binding) == 1, ResultCode.DATA_CONCURRENT_CONFLICT);
        }
        for (String id : bindings.buttons()) {
            SysRoleButton binding = new SysRoleButton();
            binding.setRoleId(roleId);
            binding.setButtonId(id);
            ServiceAssert.isTrue(
                    roleButtonMapper.insert(binding) == 1, ResultCode.DATA_CONCURRENT_CONFLICT);
        }
    }

    private void deleteBindings(String roleId) {
        roleMenuMapper.delete(
                new LambdaQueryWrapper<SysRoleMenu>().eq(SysRoleMenu::getRoleId, roleId));
        roleButtonMapper.delete(
                new LambdaQueryWrapper<SysRoleButton>().eq(SysRoleButton::getRoleId, roleId));
    }
}
