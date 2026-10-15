package com.robot.system.api.dto.role;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;

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
    private List<@NotBlank @Size(max = 64) String> permissionIds;

    @NotNull
    @Min(0)
    private Integer revision;

    /**
     * 返回精确授权资源ID集合。
     *
     * @return 精确授权资源ID集合
     */
    public List<String> getPermissionIds() {
        return permissionIds == null ? null : new java.util.ArrayList<>(permissionIds);
    }
    /**
     * 复制输入集合以隔离调用方后续修改。
     *
     * @param value 待校验或快照复制的输入值
     */
    public void setPermissionIds(List<String> value) {
        permissionIds = value == null ? null : new java.util.ArrayList<>(value);
    }
}
