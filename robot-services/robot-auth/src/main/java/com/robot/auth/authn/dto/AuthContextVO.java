package com.robot.auth.authn.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.robot.api.system.dto.RemoteUserContextDTO;
import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 企业工作上下文（前端 AuthContext 契约：角色只由认证服务授予，客户端不能自行提权）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class AuthContextVO implements Serializable {

    @Serial private static final long serialVersionUID = 1L;

    /** 上下文 ID（sys_user_company.ID）。 */
    private String id;

    /** 是否主公司（同一账号唯一主公司）；JSON 字段名按前端契约为 isPrimary。 */
    @JsonProperty("isPrimary")
    private Boolean primary;

    private String tenantId;

    private String tenantName;

    private String companyId;

    private String companyName;

    /** 激活后才返回角色；发现阶段为 null/空。 */
    private List<RoleVO> roles;

    /**
     * Return independent role objects; modifying frontend views cannot alter trusted snapshots.
     *
     * @return detached role list, or null before activation
     */
    public List<RoleVO> getRoles() {
        return copyRoles(roles);
    }

    /**
     * Retain the writable JSON bean contract with independent role objects.
     *
     * @param values role views, or null before activation
     */
    public void setRoles(List<RoleVO> values) {
        roles = copyRoles(values);
    }

    private static List<RoleVO> copyRoles(List<RoleVO> values) {
        if (values == null) {
            return null;
        }
        return values.stream()
                .map(role -> RoleVO.builder().id(role.getId()).name(role.getName()).build())
                .toList();
    }

    /** Builder detaches role objects before the private generated constructor receives them. */
    public static class AuthContextVOBuilder {
        /**
         * Store detached role objects.
         *
         * @param values role views
         * @return this builder
         */
        public AuthContextVOBuilder roles(List<RoleVO> values) {
            roles = copyRoles(values);
            return this;
        }
    }

    /** Public role identity and display name, independent of internal permission DTOs. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RoleVO implements Serializable {
        @Serial private static final long serialVersionUID = 1L;
        private String id;
        private String name;
    }

    /**
     * 从服务间 DTO 转换（未激活形态：剥离角色）。
     *
     * @param dto 当前有效成员上下文事实
     * @param withRoles 是否已激活，只有激活后才授予角色
     * @return 独立前端上下文视图
     */
    public static AuthContextVO fromRemote(RemoteUserContextDTO dto, boolean withRoles) {
        return AuthContextVO.builder()
                .id(dto.getContextId())
                .primary(Boolean.TRUE.equals(dto.getPrimaryFlag()))
                .tenantId(dto.getTenantId())
                .tenantName(dto.getTenantName())
                .companyId(dto.getCompanyId())
                .companyName(dto.getCompanyName())
                .roles(
                        withRoles && dto.getRoles() != null
                                ? dto.getRoles().stream()
                                        .map(
                                                r ->
                                                        RoleVO.builder()
                                                                .id(r.getId())
                                                                .name(r.getName())
                                                                .build())
                                        .toList()
                                : null)
                .build();
    }
}
