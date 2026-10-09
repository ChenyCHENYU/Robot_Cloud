package com.robot.system.role.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.robot.common.core.constant.RobotConstants;
import com.robot.common.core.domain.PageResult;
import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceAssert;
import com.robot.system.api.dto.role.RolePageQuery;
import com.robot.system.api.entity.SysRole;
import com.robot.system.api.entity.SysRoleButton;
import com.robot.system.api.entity.SysRoleMenu;
import com.robot.system.api.entity.SysTempAuthorization;
import com.robot.system.api.entity.SysUserCompany;
import com.robot.system.api.entity.SysUserRole;
import com.robot.system.api.vo.permission.DataScopeVO;
import com.robot.system.api.vo.role.RoleOptionVO;
import com.robot.system.api.vo.role.RoleUserVO;
import com.robot.system.api.vo.role.RoleVO;
import com.robot.system.permission.service.PermissionGrantPolicy;
import com.robot.system.role.mapper.SysRoleButtonMapper;
import com.robot.system.role.mapper.SysRoleMapper;
import com.robot.system.role.mapper.SysRoleMenuMapper;
import com.robot.system.role.mapper.SysTempAuthorizationMapper;
import com.robot.system.user.mapper.SysUserCompanyMapper;
import com.robot.system.user.mapper.SysUserMapper;
import com.robot.system.user.mapper.SysUserRoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 当前公司角色的查询用例；列表、资源绑定和治理视图不承载写事务。
 *
 * @author Robot Cloud
 * @since 1.0.0-SNAPSHOT
 */
@Service
@RequiredArgsConstructor
public class RoleQueryService {
    private final SysRoleMapper roleMapper;
    private final SysRoleMenuMapper roleMenuMapper;
    private final SysRoleButtonMapper roleButtonMapper;
    private final PermissionGrantPolicy grantPolicy;
    private final SysUserRoleMapper userRoleMapper;
    private final SysTempAuthorizationMapper tempAuthorizationMapper;
    private final SysUserMapper userMapper;
    private final SysUserCompanyMapper membershipMapper;
    private final Clock clock;

    /** 查询当前公司未删除角色；分页统计与结果使用相同谓词。 */
    public PageResult<RoleVO> queryPage(RolePageQuery query) {
        String companyId = grantPolicy.requireCompany();
        LambdaQueryWrapper<SysRole> wrapper = new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getCompanyId, companyId)
                .eq(SysRole::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE)
                .eq(query.getStatus() != null, SysRole::getStatusFlag, query.getStatus())
                .and(query.getKeyword() != null && !query.getKeyword().isBlank(), w -> w
                        .like(SysRole::getRoleName, query.getKeyword())
                        .or().like(SysRole::getRoleCode, query.getKeyword()))
                .orderByAsc(SysRole::getSortNo, SysRole::getId);
        Page<SysRole> page = roleMapper.selectPage(new Page<>(query.pageValue(), query.pageSizeValue()), wrapper);
        return PageResult.of(page.getRecords().stream().map(RoleVO::from).toList(),
                page.getTotal(), query.pageValue(), query.pageSizeValue());
    }

    /** 仅提供当前公司启用角色选项，选择后仍须经过写入授权上界校验。 */
    public List<RoleOptionVO> options() {
        return roleMapper.selectList(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getCompanyId, grantPolicy.requireCompany())
                .eq(SysRole::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE)
                .eq(SysRole::getStatusFlag, 1).orderByAsc(SysRole::getSortNo, SysRole::getId))
                .stream().map(role -> new RoleOptionVO(role.getId(), role.getRoleName(), role.getRoleCode()))
                .toList();
    }

    /** 返回当前公司角色版本与菜单、按钮精确绑定，供编辑命令携带版本。 */
    public RoleVO getDetail(String id) {
        SysRole role = requireRole(id, grantPolicy.requireCompany());
        List<String> menuIds = roleMenuMapper.selectList(new LambdaQueryWrapper<SysRoleMenu>()
                .eq(SysRoleMenu::getRoleId, id).eq(SysRoleMenu::getDeleteFlag, 1))
                .stream().map(SysRoleMenu::getMenuId).toList();
        List<String> buttonIds = roleButtonMapper.selectList(new LambdaQueryWrapper<SysRoleButton>()
                .eq(SysRoleButton::getRoleId, id).eq(SysRoleButton::getDeleteFlag, 1))
                .stream().map(SysRoleButton::getButtonId).toList();
        RoleVO vo = RoleVO.from(role);
        vo.setMenuIds(menuIds);
        vo.setButtonIds(buttonIds);
        vo.setPermissionIds(java.util.stream.Stream.concat(menuIds.stream(), buttonIds.stream()).distinct().toList());
        return vo;
    }

    /** 查询当前公司仍有成员关系的角色绑定用户，排除外公司绑定。 */
    public List<RoleUserVO> roleUsers(String roleId) {
        String companyId = grantPolicy.requireCompany();
        requireRole(roleId, companyId);
        Set<String> members = membershipMapper.selectList(new LambdaQueryWrapper<SysUserCompany>()
                .eq(SysUserCompany::getCompanyId, companyId).eq(SysUserCompany::getDeleteFlag, 1))
                .stream().map(SysUserCompany::getUserId).collect(Collectors.toSet());
        List<String> ids = userRoleMapper.selectList(new LambdaQueryWrapper<SysUserRole>()
                .eq(SysUserRole::getCompanyId, companyId).eq(SysUserRole::getRoleId, roleId)
                .eq(SysUserRole::getDeleteFlag, 1)).stream().map(SysUserRole::getUserId)
                .filter(members::contains).distinct().toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        return userMapper.selectBatchIds(ids).stream().map(user -> new RoleUserVO(user.getId(), user.getUsername(),
                user.getNickname(), Integer.valueOf(1).equals(user.getStatusFlag()) ? "active" : "disabled")).toList();
    }

    /** 展示角色配置范围；该返回值本身不执行业务数据权限过滤。 */
    public List<DataScopeVO> dataScopes(String roleId) {
        SysRole role = requireRole(roleId, grantPolicy.requireCompany());
        return List.of(new DataScopeVO("*", role.getDataScope() == null ? "self" : role.getDataScope()));
    }

    /** 查询当前公司指定角色的临时授权记录，管理视图保留已过期记录。 */
    public List<SysTempAuthorization> tempAuthorizations(String roleId) {
        String companyId = grantPolicy.requireCompany();
        requireRole(roleId, companyId);
        return tempAuthorizationMapper.selectList(new LambdaQueryWrapper<SysTempAuthorization>()
                .eq(SysTempAuthorization::getCompanyId, companyId).eq(SysTempAuthorization::getRoleId, roleId)
                .eq(SysTempAuthorization::getDeleteFlag, 1)
                .orderByDesc(SysTempAuthorization::getCreateDateTime, SysTempAuthorization::getId));
    }

    /** 查询当前公司临时角色授权记录，不扩展为临时权限模型。 */
    public List<SysTempAuthorization> allTempAuthorizations() {
        return tempAuthorizationMapper.selectList(new LambdaQueryWrapper<SysTempAuthorization>()
                .eq(SysTempAuthorization::getCompanyId, grantPolicy.requireCompany())
                .eq(SysTempAuthorization::getDeleteFlag, 1)
                .orderByDesc(SysTempAuthorization::getCreateDateTime, SysTempAuthorization::getId));
    }

    /** 仅读取指定公司用户在当前半开时间窗内生效的临时角色授权。 */
    public List<SysTempAuthorization> activeTempAuthorizations(String userId, String companyId) {
        ServiceAssert.isTrue(grantPolicy.requireCompany().equals(companyId), ResultCode.FORBIDDEN);
        LocalDateTime now = LocalDateTime.now(clock);
        return tempAuthorizationMapper.selectList(new LambdaQueryWrapper<SysTempAuthorization>()
                .eq(SysTempAuthorization::getCompanyId, companyId).eq(SysTempAuthorization::getUserId, userId)
                .eq(SysTempAuthorization::getStatusFlag, 1).eq(SysTempAuthorization::getDeleteFlag, 1)
                .le(SysTempAuthorization::getEffectFrom, now).gt(SysTempAuthorization::getEffectTo, now));
    }    private SysRole requireRole(String id, String companyId) {
        ServiceAssert.notBlank(id, ResultCode.PARAM_INVALID, "角色ID不能为空");
        SysRole role = ServiceAssert.notNull(roleMapper.selectById(id), ResultCode.ROLE_NOT_FOUND);
        ServiceAssert.isTrue(companyId.equals(role.getCompanyId()) && Integer.valueOf(1).equals(role.getDeleteFlag()),
                ResultCode.FORBIDDEN, "角色不属于当前公司");
        return role;
    }

}
