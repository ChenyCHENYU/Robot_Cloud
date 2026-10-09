package com.robot.system.permission.service;

import com.robot.common.security.util.AuthUtil;
import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceAssert;
import com.robot.system.api.entity.SysPermissionAuditLog;
import com.robot.system.mapper.SysPermissionAuditLogMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Set;

/**
 * 权限变更记录器：全部权限类变更（角色/授权/用户重绑/菜单/按钮）的唯一入口。
 *
 * <p>职责收敛：一次调用同时完成
 * <ol>
 *   <li>审计留痕（sys_permission_audit_log 只追加，操作人兜底 SYSTEM）；</li>
 *   <li>数据库 epoch 与可靠待发记录同事务写入，提交后再通知缓存。</li>
 * </ol>
 * 业务服务不再各自维护 audit/evict 拷贝（历史上曾因此漏审用户与菜单变更）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PermissionChangeRecorder {

    private final SysPermissionAuditLogMapper auditLogMapper;
    private final CommittedCacheChanges cacheChanges;
    private final ObjectMapper objectMapper;

    private static final Set<String> SECRET_FIELDS = Set.of("password", "passwordhash",
            "oldpassword", "newpassword", "token", "accesstoken", "refreshtoken", "secret");

    /**
     * 记录一次权限变更。
     *
     * @param action 动作（grant_roles / save_role / delete_role / assign_permissions /
     *               temp_authorize / save_menu / delete_menu / save_button / delete_button）
     * @param target 目标对象标识
     * @param detail 变更详情（JSON 序列化落库，可为 null）
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void record(String action, String target, Object detail) {
        String companyId = AuthUtil.getLoginCompanyId();
        ServiceAssert.notBlank(companyId, ResultCode.UNAUTHORIZED);
        SysPermissionAuditLog entry = new SysPermissionAuditLog();
        entry.setCompanyId(companyId);
        entry.setActorUserNo(AuthUtil.getLoginUsername() == null ? "SYSTEM" : AuthUtil.getLoginUsername());
        entry.setAction(action);
        entry.setTarget(target);
        entry.setDetail(safeDetail(detail));
        ServiceAssert.isTrue(auditLogMapper.insert(entry) == 1, ResultCode.FAILED, "审计写入失败");
        cacheChanges.permissionsChanged();
    }

    private String safeDetail(Object detail) {
        if (detail == null) {
            return null;
        }
        JsonNode node = objectMapper.valueToTree(detail);
        redact(node);
        String json = node.toString();
        // 超长详情保持合法 JSON；避免 VARCHAR(2000) 截断使敏感业务丢审计。
        return json.length() <= 2000 ? json
                : "{\"truncated\":true,\"originalLength\":" + json.length() + "}";
    }

    private static void redact(JsonNode node) {
        if (node instanceof ObjectNode object) {
            object.fieldNames().forEachRemaining(name -> {
                if (SECRET_FIELDS.contains(name.toLowerCase(Locale.ROOT))) {
                    object.put(name, "[REDACTED]");
                } else {
                    redact(object.get(name));
                }
            });
        } else if (node.isArray()) {
            node.forEach(PermissionChangeRecorder::redact);
        }
    }
}
