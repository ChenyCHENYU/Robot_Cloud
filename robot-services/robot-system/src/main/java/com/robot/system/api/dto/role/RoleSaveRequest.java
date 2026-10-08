package com.robot.system.api.dto.role;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/** 角色新增/编辑请求。 */
@Data
@NoArgsConstructor
public class RoleSaveRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;

    @NotBlank(message = "角色名不能为空")
    @Size(max = 30, message = "角色名最长 30")
    private String roleName;

    @NotBlank(message = "角色编码不能为空")
    @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,49}$", message = "角色编码为大写字母开头的字母数字下划线")
    private String roleCode;

    /** all/department/department_below/self/custom。 */
    private String dataScope;

    private Integer sortNo;

    private Integer status;

    private String remark;

    /**
     * 授权资源 ID 集合（RC11：菜单 ID 与按钮 ID 混合，按存在性分流绑定；
     * 兼容旧字段名 menuIds 读取）。
     */
    private List<String> permissionIds;

    /** 兼容旧字段（= permissionIds）。 */
    private List<String> menuIds;

    public List<String> effectivePermissionIds() {
        return permissionIds != null ? permissionIds : menuIds;
    }
}
