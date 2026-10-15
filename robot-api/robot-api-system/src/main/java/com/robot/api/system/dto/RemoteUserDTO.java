package com.robot.api.system.dto;

import java.io.Serial;
import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 内部服务间传输的用户视图（含密码摘要，仅限服务间内网调用，禁止外泄到前端）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RemoteUserDTO implements Serializable {

    @Serial private static final long serialVersionUID = 1L;

    private String id;

    private String username;

    /** BCrypt 密码摘要。 */
    @lombok.ToString.Exclude private String passwordHash;

    private String nickname;

    private String email;

    private String phone;

    private String departmentId;

    /** 账号状态：1=启用，0=停用。 */
    private Integer statusFlag;

    /** 持久化账号撤销版本，认证服务签发时必须保存。 */
    private Long securityVersion;
}
