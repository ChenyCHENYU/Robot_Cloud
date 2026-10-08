package com.robot.auth.authn.dto;

import com.robot.api.system.dto.RemoteUserContextDTO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 企业工作上下文（前端 AuthContext 契约：角色只由认证服务授予，客户端不能自行提权）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthContextVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

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

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RoleVO implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String id;
        private String name;
    }

    /** 从服务间 DTO 转换（未激活形态：剥离角色）。 */
    public static AuthContextVO fromRemote(RemoteUserContextDTO dto, boolean withRoles) {
        return AuthContextVO.builder()
                .id(dto.getContextId())
                .primary(Boolean.TRUE.equals(dto.getPrimaryFlag()))
                .tenantId(dto.getTenantId())
                .tenantName(dto.getTenantName())
                .companyId(dto.getCompanyId())
                .companyName(dto.getCompanyName())
                .roles(withRoles && dto.getRoles() != null
                        ? dto.getRoles().stream()
                        .map(r -> RoleVO.builder().id(r.getId()).name(r.getName()).build())
                        .toList()
                        : null)
                .build();
    }
}
