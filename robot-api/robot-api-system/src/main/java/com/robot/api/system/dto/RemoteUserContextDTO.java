package com.robot.api.system.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 用户在某公司上下文下的授权视图（认证服务登录时取用）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RemoteUserContextDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 成员关系 ID（sys_user_company.ID，即前端 contextId）。 */
    private String contextId;

    /** 是否主公司。 */
    private Boolean primaryFlag;

    private String tenantId;

    private String tenantName;

    private String companyId;

    private String companyName;

    /** 该公司下授予的角色（编码 + 名称）。 */
    private List<RoleItem> roles;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RoleItem implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String id;
        private String code;
        private String name;
    }
}
