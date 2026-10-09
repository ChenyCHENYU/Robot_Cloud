package com.robot.system.api.dto.role;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 精确权限重绑命令；revision锁住角色及其关联，空集合表示全部清空。
 *
 * @author Robot Cloud
 * @since 1.0.0-SNAPSHOT
 */
@Data
public class PermissionAssignRequest {
    @NotNull
    @Size(max = 500)
    private List<
            @NotBlank
            @Size(max = 64)
            String> permissionIds;

    @NotNull
    @Min(0)
    private Integer revision;
}
