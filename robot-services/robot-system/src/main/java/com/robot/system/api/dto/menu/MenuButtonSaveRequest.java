package com.robot.system.api.dto.menu;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.*;
import lombok.Data;

/** 按钮属于单个有效页面，权限码只允许精确资源动作，不接受通配符。 */
@Data
public class MenuButtonSaveRequest {
    @Size(max = 64) private String menuId;
    @NotBlank @Size(max = 60) @JsonAlias("buttonName") private String name;
    @NotBlank @Size(max = 100) @Pattern(regexp = "[\\w.-]+(?::[\\w.-]+)+")
    @JsonAlias("permCode") private String permission;
    @Min(0) @Max(9999) @JsonAlias("sortNo") private Integer sort;
    @Min(0) @Max(1) @JsonAlias("statusFlag") private Integer status;
    @Size(max = 255) private String remark;
    @Min(0) private Integer revision;
}
