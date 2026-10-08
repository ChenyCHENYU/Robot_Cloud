package com.robot.system.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.robot.common.core.constant.RobotConstants;
import com.robot.common.core.domain.PageResult;
import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceAssert;
import com.robot.common.core.exception.ServiceException;
import com.robot.common.security.util.AuthUtil;
import com.robot.system.api.dto.user.UserPageQuery;
import com.robot.system.api.dto.user.UserSaveRequest;
import com.robot.system.api.entity.SysRole;
import com.robot.system.api.entity.SysUser;
import com.robot.system.api.entity.SysUserCompany;
import com.robot.system.api.entity.SysUserRole;
import com.robot.system.api.vo.user.UserVO;
import com.robot.system.user.mapper.SysUserCompanyMapper;
import com.robot.system.user.mapper.SysUserMapper;
import com.robot.system.user.mapper.SysUserRoleMapper;
import com.robot.system.role.mapper.SysRoleMapper;
import com.robot.system.permission.service.PermissionChangeRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 用户管理：查询走租户拦截器自动过滤 COMPANY_ID；
 * 用户/成员/角色绑定跨三表写必须收敛在同一事务（wl-skills-bd standards/10）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService extends ServiceImpl<SysUserMapper, SysUser> {

    /** 内置管理员账号（种子数据），禁止删除/停用，防系统自锁。 */
    public static final String BUILTIN_ADMIN_USERNAME = "admin";

    private final SysUserCompanyMapper userCompanyMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysRoleMapper roleMapper;
    private final BCryptPasswordEncoder passwordEncoder;
    private final PermissionChangeRecorder changeRecorder;

    public PageResult<UserVO> queryPage(UserPageQuery query) {
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE)
                .eq(query.getStatus() != null, SysUser::getStatusFlag, query.getStatus())
                .eq(query.getDeptId() != null && !query.getDeptId().isBlank(),
                        SysUser::getDepartmentId, query.getDeptId())
                .and(query.getKeyword() != null && !query.getKeyword().isBlank(), w -> w
                        .like(SysUser::getUsername, query.getKeyword())
                        .or().like(SysUser::getNickname, query.getKeyword())
                        .or().like(SysUser::getEmail, query.getKeyword()))
                .orderByDesc(SysUser::getCreateDateTime);

        Page<SysUser> page = page(new Page<>(query.pageValue(), query.pageSizeValue()), wrapper);
        return PageResult.of(toVos(page.getRecords()), page.getTotal(),
                query.pageValue(), query.pageSizeValue());
    }

    public UserVO getDetail(String id) {
        SysUser user = ServiceAssert.notNull(getById(id), ResultCode.USER_NOT_FOUND);
        return toVos(List.of(user)).get(0);
    }

    @Transactional(rollbackFor = Exception.class)
    public String create(UserSaveRequest request) {
        ServiceAssert.isTrue(count(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, request.getUsername())
                .eq(SysUser::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE)) == 0,
                ResultCode.USER_USERNAME_DUPLICATED);
        ServiceAssert.notNull(request.getPassword(), ResultCode.PARAM_INVALID, "新增用户必须提供初始密码");
        String companyId = AuthUtil.getLoginCompanyId();
        ServiceAssert.notBlank(companyId, ResultCode.FORBIDDEN, "缺少公司上下文，禁止创建用户");

        SysUser user = new SysUser();
        user.setUsername(request.getUsername());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        applyMutableFields(user, request);
        save(user);

        // 加入当前公司（首个成员关系默认主公司）
        SysUserCompany membership = new SysUserCompany();
        membership.setUserId(user.getId());
        membership.setCompanyId(companyId);
        membership.setPrimaryFlag(1);
        membership.setStatusFlag(1);
        userCompanyMapper.insert(membership);

        grantRoles(user.getId(), request.getRoleIds());
        changeRecorder.record("grant_roles", user.getId(),
                Map.of("username", request.getUsername(),
                        "roleCount", request.getRoleIds() == null ? 0 : request.getRoleIds().size()));
        return user.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(String id, UserSaveRequest request) {
        SysUser user = ServiceAssert.notNull(getById(id), ResultCode.USER_NOT_FOUND);
        ServiceAssert.isTrue(count(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, request.getUsername())
                .eq(SysUser::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE)
                .ne(SysUser::getId, id)) == 0,
                ResultCode.USER_USERNAME_DUPLICATED);
        guardBuiltinAccount(user, "编辑");

        applyMutableFields(user, request);
        // 编辑留空表示不改密码
        if (request.getPassword() != null && !request.getPassword().isBlank()) {
            user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        }
        updateById(user);

        // 角色重绑：先删后插（事务内）
        userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>()
                .eq(SysUserRole::getUserId, id));
        grantRoles(id, request.getRoleIds());
        changeRecorder.record("rebind_roles", id,
                Map.of("roleCount", request.getRoleIds() == null ? 0 : request.getRoleIds().size()));
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(String id) {
        SysUser user = ServiceAssert.notNull(getById(id), ResultCode.USER_NOT_FOUND);
        guardBuiltinAccount(user, "删除");
        ServiceAssert.isTrue(!id.equals(AuthUtil.getLoginUserId()),
                ResultCode.PARAM_INVALID, "禁止删除当前登录账号");
        // MP 逻辑删除：UPDATE sys_user SET delete_flag=0 WHERE id=? AND delete_flag=1
        removeById(id);
        // 成员关系与角色绑定同步失效
        SysUserCompany membership = new SysUserCompany();
        membership.setStatusFlag(0);
        userCompanyMapper.update(membership, new LambdaQueryWrapper<SysUserCompany>()
                .eq(SysUserCompany::getUserId, id));
        userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>()
                .eq(SysUserRole::getUserId, id));
        changeRecorder.record("delete_user", id, null);
    }

    /** 管理员重置密码（敏感写：审计语义走操作日志，前端二次确认）。 */
    @Transactional(rollbackFor = Exception.class)
    public void resetPassword(String id, String password) {
        ServiceAssert.notNull(password, ResultCode.PARAM_INVALID, "新密码不能为空");
        ServiceAssert.isTrue(password.length() >= 6 && password.length() <= 32,
                ResultCode.PARAM_INVALID, "密码长度 6~32");
        SysUser user = ServiceAssert.notNull(getById(id), ResultCode.USER_NOT_FOUND);
        user.setPasswordHash(passwordEncoder.encode(password));
        updateById(user);
    }

    /** 启用/停用（停用后登录被拒绝；内置 admin 禁止停用）。 */
    @Transactional(rollbackFor = Exception.class)
    public void toggleStatus(String id, Integer status) {
        ServiceAssert.isTrue(status != null && (status == 0 || status == 1),
                ResultCode.PARAM_INVALID, "状态值只允许 0/1");
        SysUser user = ServiceAssert.notNull(getById(id), ResultCode.USER_NOT_FOUND);
        if (status == 0) {
            guardBuiltinAccount(user, "停用");
            ServiceAssert.isTrue(!id.equals(AuthUtil.getLoginUserId()),
                    ResultCode.PARAM_INVALID, "禁止停用当前登录账号");
        }
        user.setStatusFlag(status);
        updateById(user);
    }

    private void guardBuiltinAccount(SysUser user, String action) {
        if (BUILTIN_ADMIN_USERNAME.equals(user.getUsername())) {
            throw new ServiceException(ResultCode.FORBIDDEN,
                    "内置管理员账号禁止" + action);
        }
    }

    private void applyMutableFields(SysUser user, UserSaveRequest request) {
        user.setNickname(request.getNickname());
        user.setEmail(request.getEmail());
        user.setPhone(request.getPhone());
        user.setDepartmentId(request.getDepartmentId());
        user.setStatusFlag(request.getStatus() == null ? 1 : request.getStatus());
    }

    private void grantRoles(String userId, List<String> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return;
        }
        ServiceAssert.isTrue(roleIds.size() <= RobotConstants.BATCH_WRITE_LIMIT,
                ResultCode.PARAM_INVALID, "角色数量超出单批上限");
        // 角色必须属于当前公司（防越权挂载他司角色）
        List<String> validRoleIds = roleMapper.selectList(new LambdaQueryWrapper<SysRole>()
                        .in(SysRole::getId, roleIds)
                        .eq(SysRole::getCompanyId, AuthUtil.getLoginCompanyId())
                        .eq(SysRole::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE))
                .stream().map(SysRole::getId).toList();
        ServiceAssert.isTrue(validRoleIds.size() == roleIds.stream().distinct().count(),
                ResultCode.FORBIDDEN, "包含无效或跨公司角色");
        for (String roleId : validRoleIds) {
            SysUserRole binding = new SysUserRole();
            binding.setUserId(userId);
            binding.setRoleId(roleId);
            userRoleMapper.insert(binding);
        }
    }

    /** VO 批量转换：绑定 + 角色各一次查询（消除 N+1 与同表重复查询）。 */
    private List<UserVO> toVos(List<SysUser> users) {
        if (users.isEmpty()) {
            return List.of();
        }
        // 查询 1：本页用户的全部有效绑定
        List<SysUserRole> bindings = userRoleMapper.selectList(
                new LambdaQueryWrapper<SysUserRole>()
                        .in(SysUserRole::getUserId, users.stream().map(SysUser::getId).toList())
                        .eq(SysUserRole::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE));
        // 查询 2：绑定角色实体（批量）
        Map<String, SysRole> roleById = bindings.isEmpty() ? Map.of()
                : roleMapper.selectBatchIds(bindings.stream()
                        .map(SysUserRole::getRoleId).distinct().toList()).stream()
                .filter(role -> role.getDeleteFlag().equals(RobotConstants.DELETE_FLAG_ACTIVE))
                .collect(Collectors.toMap(SysRole::getId, Function.identity()));
        Map<String, List<String>> roleNamesByUser = bindings.stream()
                .collect(Collectors.groupingBy(SysUserRole::getUserId,
                        Collectors.mapping(binding -> {
                            SysRole role = roleById.get(binding.getRoleId());
                            return role == null ? null : role.getRoleName();
                        }, Collectors.filtering(name -> name != null, Collectors.toList()))));
        return users.stream()
                .map(user -> UserVO.from(user,
                        roleNamesByUser.getOrDefault(user.getId(), List.of())))
                .toList();
    }


    /** 自助修改密码：校验旧密码。 */
    @Transactional(rollbackFor = Exception.class)
    public void changeOwnPassword(String userId, String oldPassword, String newPassword) {
        ServiceAssert.notNull(newPassword, ResultCode.PARAM_INVALID, "新密码不能为空");
        ServiceAssert.isTrue(newPassword.length() >= 6 && newPassword.length() <= 32,
                ResultCode.PARAM_INVALID, "密码长度 6~32");
        SysUser user = ServiceAssert.notNull(getById(userId), ResultCode.USER_NOT_FOUND);
        ServiceAssert.isTrue(
                passwordEncoder.matches(oldPassword == null ? "" : oldPassword,
                        user.getPasswordHash()),
                ResultCode.PARAM_INVALID, "原密码不正确");
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        updateById(user);
    }

    /** 自助更新资料（昵称/邮箱/手机号）。 */
    @Transactional(rollbackFor = Exception.class)
    public void updateOwnProfile(String userId, String nickname, String email, String phone) {
        SysUser user = ServiceAssert.notNull(getById(userId), ResultCode.USER_NOT_FOUND);
        if (nickname != null && !nickname.isBlank()) {
            user.setNickname(nickname);
        }
        user.setEmail(email);
        user.setPhone(phone);
        updateById(user);
    }
}
