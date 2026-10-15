package com.robot.system.account.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.robot.common.core.constant.RobotConstants;
import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceAssert;
import com.robot.common.security.util.AuthUtil;
import com.robot.system.account.mapper.SysLoginLogMapper;
import com.robot.system.api.dto.account.PasswordChangeRequest;
import com.robot.system.api.dto.account.ProfileUpdateRequest;
import com.robot.system.api.entity.SysLoginLog;
import com.robot.system.api.entity.SysPermissionAuditLog;
import com.robot.system.api.entity.SysUser;
import com.robot.system.api.vo.account.AccountProfileVO;
import com.robot.system.api.vo.account.ActivityLogVO;
import com.robot.system.api.vo.account.LoginRecordVO;
import com.robot.system.api.vo.account.SecuritySettingVO;
import com.robot.system.mapper.SysPermissionAuditLogMapper;
import com.robot.system.permission.service.EffectiveRoleResolver;
import com.robot.system.user.service.UserService;
import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 当前登录账户自助业务；查询、主体与审计作用域统一在服务层。 */
@Service
@RequiredArgsConstructor
public class AccountService {
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final UserService userService;
    private final OwnAccountCommandService ownCommands;
    private final SysLoginLogMapper loginLogMapper;
    private final SysPermissionAuditLogMapper auditLogMapper;
    private final EffectiveRoleResolver roles;

    /**
     * 只读取当前登录账号；角色名称使用当前有效角色而非会话旧快照。
     *
     * @return 当前事实派生的结果
     */
    public AccountProfileVO profile() {
        String userId = AuthUtil.getLoginUserId();
        ServiceAssert.notBlank(userId, ResultCode.UNAUTHORIZED);
        SysUser user =
                ServiceAssert.notNull(userService.getById(userId), ResultCode.USER_NOT_FOUND);

        SysLoginLog lastLogin =
                loginLogMapper
                        .selectList(
                                new LambdaQueryWrapper<SysLoginLog>()
                                        .eq(SysLoginLog::getUserId, userId)
                                        .eq(SysLoginLog::getStatusFlag, 1)
                                        .eq(SysLoginLog::getLoginType, "login")
                                        .orderByDesc(SysLoginLog::getLoginTime)
                                        .last("LIMIT 1"))
                        .stream()
                        .findFirst()
                        .orElse(null);

        return new AccountProfileVO(
                user.getUsername(),
                user.getNickname(),
                user.getEmail(),
                user.getPhone(),
                "",
                "",
                roles.resolve(userId, AuthUtil.getLoginCompanyId()).superAdmin() ? "超级管理员" : "普通用户",
                userService.currentMemberDepartment(userId),
                user.getCreateDateTime() == null ? "" : TIME.format(user.getCreateDateTime()),
                lastLogin == null ? "" : TIME.format(lastLogin.getLoginTime()),
                lastLogin == null || lastLogin.getIp() == null ? "" : lastLogin.getIp());
    }

    /**
     * 自助资料写入使用UserService的归属、白名单和强审计规则。
     *
     * @param request 该用例接收的白名单请求
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateProfile(ProfileUpdateRequest request) {
        ownCommands.updateProfile(AuthUtil.getLoginUserId(), request);
    }

    /**
     * 校验旧密码后更新摘要和账号撤销版本，审计失败回滚。
     *
     * @param request 该用例接收的白名单请求
     */
    @Transactional(rollbackFor = Exception.class)
    public void changePassword(PasswordChangeRequest request) {
        ownCommands.changePassword(
                AuthUtil.getLoginUserId(), request.getOldPassword(), request.getNewPassword());
    }

    /**
     * 返回当前账号在当前公司内最近20条登录记录。
     *
     * @return 当前事实派生的结果
     */
    public List<LoginRecordVO> loginRecords() {
        String userId = AuthUtil.getLoginUserId();
        ServiceAssert.notBlank(userId, ResultCode.UNAUTHORIZED);
        return loginLogMapper
                .selectList(
                        new LambdaQueryWrapper<SysLoginLog>()
                                .eq(SysLoginLog::getUserId, userId)
                                .orderByDesc(SysLoginLog::getLoginTime)
                                .last("LIMIT 20"))
                .stream()
                .map(
                        entry ->
                                new LoginRecordVO(
                                        entry.getId(),
                                        TIME.format(entry.getLoginTime()),
                                        entry.getIp() == null ? "" : entry.getIp(),
                                        "",
                                        entry.getUserAgent() == null
                                                ? ""
                                                : deviceOf(entry.getUserAgent()),
                                        entry.getUserAgent() == null
                                                ? ""
                                                : browserOf(entry.getUserAgent()),
                                        entry.getStatusFlag() != null && entry.getStatusFlag() == 1
                                                ? "success"
                                                : "failed"))
                .toList();
    }

    /**
     * 展示已实现的认证能力与尚未实现的双因素认证。
     *
     * @return 当前事实派生的结果
     */
    public List<SecuritySettingVO> securitySettings() {
        return List.of(
                new SecuritySettingVO(
                        "password-strength",
                        "密码强度策略",
                        "长度6~32且UTF-8最多72字节，BCrypt摘要存储",
                        "shield",
                        true),
                new SecuritySettingVO("session-timeout", "会话自动过期", "访问令牌 30 分钟无感刷新", "timer", true),
                new SecuritySettingVO("refresh-rotation", "刷新令牌轮换", "每次刷新旧令牌立即作废", "refresh", true),
                new SecuritySettingVO("two-factor", "双因素认证", "登录二次验证（规划中）", "lock", false));
    }

    /**
     * 查询当前公司的本人审计记录，所有主体字段必须来自登录态。
     *
     * @return 当前事实派生的结果
     */
    public List<ActivityLogVO> activityLogs() {
        ServiceAssert.notBlank(AuthUtil.getLoginUserId(), ResultCode.UNAUTHORIZED);
        ServiceAssert.notBlank(AuthUtil.getLoginUsername(), ResultCode.UNAUTHORIZED);
        ServiceAssert.notBlank(AuthUtil.getLoginCompanyId(), ResultCode.FORBIDDEN);
        return auditLogMapper
                .selectList(
                        new LambdaQueryWrapper<SysPermissionAuditLog>()
                                .eq(
                                        SysPermissionAuditLog::getActorUserNo,
                                        AuthUtil.getLoginUsername())
                                .eq(
                                        SysPermissionAuditLog::getDeleteFlag,
                                        RobotConstants.DELETE_FLAG_ACTIVE)
                                .orderByDesc(SysPermissionAuditLog::getCreateDateTime)
                                .last("LIMIT 20"))
                .stream()
                .map(
                        entry ->
                                new ActivityLogVO(
                                        entry.getId(),
                                        TIME.format(entry.getCreateDateTime()),
                                        actionTypeOf(entry.getAction()),
                                        "sys",
                                        entry.getAction()
                                                + " "
                                                + (entry.getTarget() == null
                                                        ? ""
                                                        : entry.getTarget()),
                                        "",
                                        "success"))
                .toList();
    }

    private static String deviceOf(String userAgent) {
        return userAgent.contains("Mobile") ? "移动端" : "桌面端";
    }

    private static String browserOf(String userAgent) {
        if (userAgent.contains("Edg/")) {
            return "Edge";
        }
        if (userAgent.contains("Chrome/")) {
            return "Chrome";
        }
        if (userAgent.contains("Firefox/")) {
            return "Firefox";
        }
        if (userAgent.contains("Safari/")) {
            return "Safari";
        }
        return "未知";
    }

    private static String actionTypeOf(String action) {
        if (action == null) {
            return "other";
        }
        if (action.contains("save") || action.contains("assign") || action.contains("grant")) {
            return "create";
        }
        if (action.contains("update") || action.contains("rebind") || action.contains("move")) {
            return "update";
        }
        if (action.contains("delete")) {
            return "delete";
        }
        return "other";
    }
}
