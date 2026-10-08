package com.robot.system.role.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.robot.common.core.constant.RobotConstants;
import com.robot.system.api.constant.SystemConstants;
import com.robot.common.core.domain.PageResult;
import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceAssert;
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
import com.robot.system.role.mapper.SysTempAuthorizationMapper;
import com.robot.system.user.mapper.SysUserMapper;
import com.robot.system.user.mapper.SysUserRoleMapper;
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
        bindMenus(role.getId(), request.getMenuIds());
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
        roleMenuMapper.delete(new LambdaQueryWrapper<SysRoleMenu>()
                .eq(SysRoleMenu::getRoleId, id));
        bindMenus(id, request.getMenuIds());
        changeRecorder.record("update_role", id, request);
    }

    /** 角色权限批量授权（前端 PUT /sys/roles/{id}/permissions {permissionIds}）。 */
    @Transactional(rollbackFor = Exception.class)
    public void assignPermissions(String roleId, List<String> permissionIds) {
        SysRole role = ServiceAssert.notNull(getById(roleId), ResultCode.ROLE_NOT_FOUND);
        roleMenuMapper.delete(new LambdaQueryWrapper<SysRoleMenu>()
                .eq(SysRoleMenu::getRoleId, roleId));
        bindMenus(roleId, permissionIds);
        changeRecorder.record("assign_permissions", roleId,
                Map.of("menuCount", permissionIds == null ? 0 : permissionIds.size()));
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
        roleMenuMapper.delete(new LambdaQueryWrapper<SysRoleMenu>()
                .eq(SysRoleMenu::getRoleId, id));
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

    private void bindMenus(String roleId, List<String> menuIds) {
        if (menuIds == null || menuIds.isEmpty()) {
            return;
        }
        for (String menuId : menuIds) {
            SysRoleMenu binding = new SysRoleMenu();
            binding.setRoleId(roleId);
            binding.setMenuId(menuId);
            roleMenuMapper.insert(binding);
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
