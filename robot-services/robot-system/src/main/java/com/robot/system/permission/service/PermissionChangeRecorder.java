package com.robot.system.permission.service;

import com.robot.common.security.util.AuthUtil;
import com.robot.common.redis.core.RedisHelper;
import com.robot.system.api.entity.SysPermissionAuditLog;
import com.robot.system.mapper.SysPermissionAuditLogMapper;
import com.robot.system.support.SystemKeys;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 权限变更记录器：全部权限类变更（角色/授权/用户重绑/菜单/按钮）的唯一入口。
 *
 * <p>职责收敛：一次调用同时完成
 * <ol>
 *   <li>审计留痕（sys_permission_audit_log 只追加，操作人兜底 SYSTEM）；</li>
 *   <li>权限缓存版本自增（全量惰性失效，键见 SystemKeys.userPermissions）。</li>
 * </ol>
 * 业务服务不再各自维护 audit/evict 拷贝（历史上曾因此漏审用户与菜单变更）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PermissionChangeRecorder {

    private final SysPermissionAuditLogMapper auditLogMapper;
    private final RedisHelper redisHelper;

    /**
     * 记录一次权限变更。
     *
     * @param action 动作（grant_roles / save_role / delete_role / assign_permissions /
     *               temp_authorize / save_menu / delete_menu / save_button / delete_button）
     * @param target 目标对象标识
     * @param detail 变更详情（JSON 序列化落库，可为 null）
     */
    public void record(String action, String target, Object detail) {
        try {
            if (AuthUtil.getLoginCompanyId() != null) {
                SysPermissionAuditLog logEntry = new SysPermissionAuditLog();
                logEntry.setActorUserNo(AuthUtil.getLoginUsername() == null
                        ? "SYSTEM" : AuthUtil.getLoginUsername());
                logEntry.setAction(action);
                logEntry.setTarget(target);
                logEntry.setDetail(detail == null ? null : JSONUtil.toJsonStr(detail));
                auditLogMapper.insert(logEntry);
            } else {
                log.warn("audit skipped (no company context): {} -> {}", action, target);
            }
        } catch (Exception e) {
            // 审计失败不阻断业务事务外的主流程语义：记日志供补偿（bd 21 敏感写留痕要求尽力而为 + 告警）
            log.error("audit insert failed: {} -> {}", action, target, e);
        }
        bumpPermissionVersion();
    }

    private void bumpPermissionVersion() {
        try {
            redisHelper.bumpVersion(SystemKeys.permissionVersion());
        } catch (Exception e) {
            log.warn("bump permission version failed: {}", e.getMessage());
        }
    }
}
