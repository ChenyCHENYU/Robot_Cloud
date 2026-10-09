package com.robot.common.security.context;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * 登录会话上下文：认证成功后写入 Sa-Token Session 的用户视图。
 *
 * <p>安全约束：本对象由认证服务在登录/激活/切换时构建，服务端是唯一事实源；
 * 客户端提交的任何角色/租户字段一律不信任（wl-skills-bd standards/11）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginUser implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 用户 ID（String，VARCHAR(64) 不透明 ID）。 */
    private String userId;

    /** 登录名。 */
    private String username;

    /** 显示名。 */
    private String displayName;

    /** 当前激活租户 ID。 */
    private String tenantId;

    /** 当前激活公司 ID（数据隔离键，取值只来自本对象）。 */
    private String companyId;

    /** 当前上下文对应的成员关系 ID（sys_user_company.ID）。 */
    private String contextId;

    /** 签发时的账号撤销版本；改密后旧会话永久失效。 */
    private Long userSecurityVersion;

    /** 签发时的成员撤销版本；停用、移出后旧上下文永久失效。 */
    private Long contextSecurityVersion;

    /** 当前公司下授予的角色编码列表。 */
    @Builder.Default
    private List<String> roleCodes = Collections.emptyList();

    /** 是否超级管理员（跳过权限码匹配）。 */
    @Builder.Default
    private boolean superAdmin = false;
}
