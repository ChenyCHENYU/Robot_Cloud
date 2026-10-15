package com.robot.api.system.dto;

import java.io.Serial;
import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 登录日志内部写入载荷（robot-auth → robot-system）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RemoteLoginLogDTO implements Serializable {

    @Serial private static final long serialVersionUID = 1L;

    /** 不透明UUID，重投递幂等键；不含token、密码或请求。 */
    private String eventId;

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
