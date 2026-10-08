package com.robot.api.system.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/** 登录日志内部写入载荷（robot-auth → robot-system）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RemoteLoginLogDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String userId;

    private String username;

    private String companyId;

    /** login / activate / switch / refresh / logout。 */
    private String loginType;

    /** 1=成功，0=失败。 */
    private Integer status;

    private String ip;

    private String userAgent;
}
