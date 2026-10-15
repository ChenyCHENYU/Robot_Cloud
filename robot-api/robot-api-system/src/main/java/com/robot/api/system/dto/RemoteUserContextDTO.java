package com.robot.api.system.dto;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 用户在某公司上下文下的授权视图（认证服务登录时取用）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class RemoteUserContextDTO implements Serializable {

    /** 当前公司成员的主部门事实。 */
    private String departmentId;

    @Serial private static final long serialVersionUID = 1L;

    /** 成员关系 ID（sys_user_company.ID，即前端 contextId）。 */
    private String contextId;

    /** 持久化成员撤销版本，停用再启用不能恢复旧会话。 */
    private Long securityVersion;

    /** 是否主公司。 */
    private Boolean primaryFlag;

    private String tenantId;

    private String tenantName;

    private String companyId;

    private String companyName;

    /** 该公司下授予的角色（编码 + 名称）。 */
    private List<RoleItem> roles;

    /**
     * 返回独立角色集合，不能从调用方改写认证事实。
     *
     * @return 角色及其集合的独立副本或 null
     */
    public List<RoleItem> getRoles() {
        return copyRoles(roles);
    }

    /**
     * 保存独立角色事实，保持 Jackson 可写 Bean 契约。
     *
     * @param roles 当前公司角色集合
     */
    public void setRoles(List<RoleItem> roles) {
        this.roles = copyRoles(roles);
    }

    private static List<RoleItem> copyRoles(List<RoleItem> source) {
        if (source == null) {
            return null;
        }
        List<RoleItem> result = new ArrayList<>(source.size());
        source.forEach(
                role ->
                        result.add(
                                role == null
                                        ? null
                                        : RoleItem.builder()
                                                .id(role.id)
                                                .code(role.code)
                                                .name(role.name)
                                                .build()));
        return result;
    }

    /** 授权 Builder 对角色集合输入执行独立快照策略。 */
    public static class RemoteUserContextDTOBuilder {
        /**
         * 设置当前公司的独立角色事实。
         *
         * @param value 当前公司角色集合
         * @return 当前 Builder
         */
        public RemoteUserContextDTOBuilder roles(List<RoleItem> value) {
            this.roles = copyRoles(value);
            return this;
        }
    }

    /** 当前公司角色事实，不含其他公司的授权或认证材料。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RoleItem implements Serializable {
        @Serial private static final long serialVersionUID = 1L;
        private String id;
        private String code;
        private String name;
    }
}
