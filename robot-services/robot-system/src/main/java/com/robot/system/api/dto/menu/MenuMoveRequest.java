package com.robot.system.api.dto.menu;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** inside 为 Admin 现用名称，inner 为后端旧请求别名。 */
@Data
public class MenuMoveRequest {
    @NotBlank
    @Size(max = 64)
    private String targetId;

    @NotBlank
    @Pattern(regexp = "inside|inner|before|after")
    private String position;
    /** 浏览器中移动源资源的快照版本，树锁与客户端 CAS 分别保护拓扑和操作意图。 */
    @JsonAlias("expectedRevision")
    @NotNull
    @Min(0)
    private Integer revision;
}
