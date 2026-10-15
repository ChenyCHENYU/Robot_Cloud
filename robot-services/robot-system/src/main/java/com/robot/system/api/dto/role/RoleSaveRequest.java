package com.robot.system.api.dto.role;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 角色新增/编辑请求。
 *
 * @author Robot Cloud
 * @since 1.0.0-SNAPSHOT
 */
@Data
@NoArgsConstructor
public class RoleSaveRequest implements Serializable {

    @Serial private static final long serialVersionUID = 1L;

    private String id;

    @NotBlank(message = "角色名不能为空")
    @Size(max = 30, message = "角色名最长 30")
    @JsonAlias("name")
    private String roleName;

    @NotBlank(message = "角色编码不能为空")
    @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,49}$", message = "角色编码为大写字母开头的字母数字下划线")
    @JsonAlias("code")
    private String roleCode;

    /** 新配置支持all/department/department_below/self；旧custom不隐式改写。 */
    @Pattern(regexp = "self|department|department_below|all", message = "custom数据范围尚无配置模型")
    private String dataScope;

    @JsonAlias("sort")
    private Integer sortNo;

    @Min(0)
    @Max(1)
    private Integer status;

    @Size(max = 255)
    @JsonAlias("description")
    private String remark;

    /** 更新时必填，取自角色详情；新增忽略。 */
    @Min(0)
    private Integer revision;

    /** 授权资源 ID 集合（菜单与按钮 ID 混合，按存在性分流绑定）。 */
    @Size(max = 500)
    private List<@NotBlank @Size(max = 64) String> permissionIds;

    /** 兼容旧菜单资源字段，与buttonIds合并；不得与permissionIds同时提交。 */
    @Size(max = 500)
    private List<@NotBlank @Size(max = 64) String> menuIds;

    @Size(max = 500)
    private List<@NotBlank @Size(max = 64) String> buttonIds;

    /**
     * 创建或返回该类型的明确协作结果。
     *
     * @return 校验是否通过
     */
    @JsonIgnore
    @AssertTrue(message = "permissionIds与menuIds/buttonIds不能同时提交")
    public boolean isPermissionIdsConsistent() {
        return permissionIds == null || menuIds == null && buttonIds == null;
    }

    /**
     * 合并兼容字段；未提交任何权限字段时保留null，交由服务保留原授权。
     *
     * @return 当前事实派生的结果
     */
    public List<String> effectivePermissionIds() {
        if (permissionIds != null) {
            return getPermissionIds();
        }
        if (menuIds == null && buttonIds == null) {
            return null;
        }
        return java.util.stream.Stream.concat(
                        menuIds == null ? java.util.stream.Stream.empty() : menuIds.stream(),
                        buttonIds == null ? java.util.stream.Stream.empty() : buttonIds.stream())
                .toList();
    }

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
    /**
     * 返回精确绑定的菜单ID集合。
     *
     * @return 精确绑定的菜单ID集合
     */
    public List<String> getMenuIds() {
        return menuIds == null ? null : new java.util.ArrayList<>(menuIds);
    }
    /**
     * 复制输入集合以隔离调用方后续修改。
     *
     * @param value 待校验或快照复制的输入值
     */
    public void setMenuIds(List<String> value) {
        menuIds = value == null ? null : new java.util.ArrayList<>(value);
    }
    /**
     * 返回精确绑定的按钮ID集合。
     *
     * @return 精确绑定的按钮ID集合
     */
    public List<String> getButtonIds() {
        return buttonIds == null ? null : new java.util.ArrayList<>(buttonIds);
    }
    /**
     * 复制输入集合以隔离调用方后续修改。
     *
     * @param value 待校验或快照复制的输入值
     */
    public void setButtonIds(List<String> value) {
        buttonIds = value == null ? null : new java.util.ArrayList<>(value);
    }
}
