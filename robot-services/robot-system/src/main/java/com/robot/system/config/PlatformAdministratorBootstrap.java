package com.robot.system.config;

import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceAssert;
import com.robot.common.core.util.PasswordPolicy;
import com.robot.system.permission.service.PermissionStateStore;
import com.robot.system.support.SystemKeys;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 显式可信启动交接入口；只允许第一次指定既有有效账号，HTTP/普通 User DTO 无此字段。 */
@Component
@Order(100)
@ConditionalOnProperty(name = "robot.bootstrap.platform-admin-enabled", havingValue = "true")
@RequiredArgsConstructor
public class PlatformAdministratorBootstrap implements ApplicationRunner {
    private final Environment environment;
    private final JdbcTemplate jdbc;
    private final PermissionStateStore state;
    private final BCryptPasswordEncoder passwordEncoder;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void run(ApplicationArguments arguments) {
        String id = environment.getProperty("robot.bootstrap.platform-admin-user-id");
        ServiceAssert.isTrue(
                id != null && id.matches("[A-Za-z0-9_-]{1,64}"),
                ResultCode.PARAM_INVALID,
                "平台初始化必须显式指定有效账号");
        state.lockMenuTree();
        var accounts =
                jdbc.queryForList(
                        "SELECT id, platform_admin_flag FROM sys_user "
                                + "WHERE id = ? AND delete_flag = 1 AND status_flag = 1 FOR UPDATE",
                        id);
        ServiceAssert.isTrue(accounts.size() == 1, ResultCode.FORBIDDEN, "平台初始化账号无效");
        Object flag = accounts.get(0).get("platform_admin_flag");
        if (Boolean.TRUE.equals(flag) || flag instanceof Number value && value.intValue() == 1) {
            return;
        }
        Long existing =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM sys_user WHERE platform_admin_flag = 1 "
                                + "AND delete_flag = 1",
                        Long.class);
        ServiceAssert.isTrue(
                existing != null && existing == 0, ResultCode.FORBIDDEN, "平台已完成首次交接，禁止启动配置追加管理员");
        var companies =
                jdbc.queryForList(
                        "SELECT m.company_id FROM sys_user_company m JOIN sys_company c ON c.id ="
                                + " m.company_id AND c.delete_flag = 1 AND c.status_flag = 1 JOIN"
                                + " sys_tenant t ON t.id = c.tenant_id AND t.delete_flag = 1 AND"
                                + " t.status_flag = 1 WHERE m.user_id = ? AND m.delete_flag = 1 AND"
                                + " m.status_flag = 1 AND EXISTS (SELECT 1 FROM sys_user_role ur JOIN"
                                + " sys_role r ON r.id = ur.role_id AND r.company_id = m.company_id AND"
                                + " r.delete_flag = 1 AND r.status_flag = 1 WHERE ur.user_id ="
                                + " m.user_id AND ur.company_id = m.company_id AND ur.delete_flag = 1)"
                                + " ORDER BY m.primary_flag DESC, m.id LIMIT 1",
                        id);
        ServiceAssert.isTrue(companies.size() == 1, ResultCode.FORBIDDEN, "平台交接账号须有有效公司身份");
        String password = environment.getProperty("robot.bootstrap.platform-admin-password");
        String passwordHash = null;
        if (password != null && !password.isEmpty()) {
            PasswordPolicy.requireNewPassword(password);
            ServiceAssert.isTrue(
                    !"admin123".equals(password), ResultCode.PARAM_INVALID, "平台交接密码不得使用开发凭据");
            passwordHash = passwordEncoder.encode(password);
        }
        ServiceAssert.isTrue(
                jdbc.update(
                                "UPDATE sys_user SET platform_admin_flag = 1, revision = revision +"
                                        + " 1, password_hash = COALESCE(?, password_hash),"
                                        + " security_version = security_version + 1, update_user_no ="
                                        + " 'BOOTSTRAP', update_date_time = CURRENT_TIMESTAMP WHERE id"
                                        + " = ? AND platform_admin_flag = 0 AND delete_flag = 1 AND"
                                        + " status_flag = 1",
                                passwordHash,
                                id)
                        == 1,
                ResultCode.DATA_CONCURRENT_CONFLICT);
        ServiceAssert.isTrue(
                jdbc.update(
                                "INSERT INTO sys_permission_audit_log (id, company_id,"
                                        + " actor_user_no, action, target, detail, create_user_no,"
                                        + " create_date_time, delete_flag, revision) VALUES (?, ?,"
                                        + " 'BOOTSTRAP', 'bootstrap_platform_admin', ?,"
                                        + " '{\"source\":\"explicit-startup\"}', 'BOOTSTRAP',"
                                        + " CURRENT_TIMESTAMP, 1, 0)",
                                UUID.randomUUID().toString(),
                                companies.get(0).get("company_id"),
                                id)
                        == 1,
                ResultCode.FAILED,
                "平台初始化成功审计失败");
        long version = state.incrementVersion(PermissionStateStore.PERMISSIONS);
        state.enqueue(PermissionStateStore.PERMISSIONS, SystemKeys.permissionVersion(), version);
    }
}
