package com.robot.system.api.dto.role;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/** 临时授权创建请求（敏感写：必须携带理由，双端留痕）。 */
@Data
@NoArgsConstructor
public class TempAuthorizationCreateRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "被授权用户不能为空")
    @Size(max = 64)
    private String userId;

    @NotBlank(message = "授予角色不能为空")
    @Size(max = 64)
    private String roleId;

    @NotNull(message = "生效开始时间不能为空")
    private LocalDateTime effectFrom;

    @NotNull(message = "生效结束时间不能为空")
    private LocalDateTime effectTo;

    @NotBlank(message = "授权理由不能为空")
    @Size(max = 255)
    private String reason;
}
