package com.robot.system.api.dto.user;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.robot.common.web.validation.ValidNewPassword;
import jakarta.validation.constraints.Email;
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
import lombok.ToString;

/**
 * 用户新增/编辑请求（前端 UserFormData 契约，memberships 不在后端消费）。
 *
 * @author Robot Cloud
 * @since 1.0.0-SNAPSHOT
 */
@Data
@NoArgsConstructor
public class UserSaveRequest implements Serializable {

    @Serial private static final long serialVersionUID = 1L;

    /** 仅编辑时传递。 */
    private String id;

    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 30, message = "用户名长度 3~30")
    @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "用户名只允许字母数字下划线")
    private String username;

    /** 新增必填；编辑时留空表示不改密码。 */
    @Pattern(regexp = "^(?:$|[\\s\\S]{6,32})$", message = "密码长度6~32，编辑时可留空")
    @ValidNewPassword(optional = true)
    @ToString.Exclude
    private String password;

    @Size(max = 30, message = "昵称最长 30")
    private String nickname;

    @Email(message = "邮箱格式不正确")
    @Size(max = 100)
    @ToString.Exclude
    private String email;

    @Size(max = 20, message = "手机号最长 20")
    @ToString.Exclude
    private String phone;

    @JsonAlias("deptId")
    @Size(max = 64)
    private String departmentId;

    /** 1=启用，0=停用。 */
    @Min(0)
    @Max(1)
    private Integer status;

    /** 角色 ID 集合（当前公司内授权）。 */
    @Size(max = 500)
    private List<@NotBlank @Size(max = 64) String> roleIds;

    /** 平台账户版本；修改账户资料/密码时必填。 */
    @Min(0)
    private Integer revision;

    /** 公司成员聚合版本；重绑角色/修改成员状态时必填。 */
    @Min(0)
    private Integer membershipRevision;

    @JsonIgnore private boolean nicknamePresent;
    @JsonIgnore @ToString.Exclude private boolean emailPresent;
    @JsonIgnore @ToString.Exclude private boolean phonePresent;
    @JsonIgnore private boolean departmentIdPresent;

    /**
     * 记录昵称及其提交状态；显式null由用例拒绝，字段省略则保留原值。
     *
     * @param value 客户端显式提交的昵称值
     */
    public void setNickname(String value) {
        nickname = value;
        nicknamePresent = true;
    }

    /**
     * 记录邮箱及其提交状态；显式null表示清空，字段省略则保留原值。
     *
     * @param value 客户端显式提交的邮箱值
     */
    public void setEmail(String value) {
        email = value;
        emailPresent = true;
    }

    /**
     * 记录电话及其提交状态；显式null表示清空，字段省略则保留原值。
     *
     * @param value 客户端显式提交的电话值
     */
    public void setPhone(String value) {
        phone = value;
        phonePresent = true;
    }

    /**
     * 记录成员主部门及其提交状态；显式null表示清空，字段省略则保留原值。
     *
     * @param value 客户端显式提交的成员主部门值
     */
    public void setDepartmentId(String value) {
        departmentId = value;
        departmentIdPresent = true;
    }

    /**
     * 返回公司角色ID集合。
     *
     * @return 公司角色ID集合
     */
    public List<String> getRoleIds() {
        return roleIds == null ? null : new java.util.ArrayList<>(roleIds);
    }
    /**
     * 复制输入集合以隔离调用方后续修改。
     *
     * @param value 待校验或快照复制的输入值
     */
    public void setRoleIds(List<String> value) {
        roleIds = value == null ? null : new java.util.ArrayList<>(value);
    }
}
