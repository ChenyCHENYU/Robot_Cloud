package com.robot.system.api.dto.menu;

import jakarta.validation.constraints.*;
import lombok.Data;

/** inside 为 Admin 现用名称，inner 为后端旧请求别名。 */
@Data
public class MenuMoveRequest {
    @NotBlank @Size(max = 64) private String targetId;
    @NotBlank @Pattern(regexp = "inside|inner|before|after") private String position;
    /** 可选的浏览器树快照版本；未传时仍在同一事务树锁下原子执行。 */
    @Min(0) private Integer revision;
}
