package com.robot.system.api.dto.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/** 用户新增/编辑请求（前端 UserFormData 契约，memberships 不在后端消费）。 */
@Data
@NoArgsConstructor
public class UserSaveRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 仅编辑时传递。 */
    private String id;

    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 30, message = "用户名长度 3~30")
    @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "用户名只允许字母数字下划线")
    private String username;

    /** 新增必填；编辑时留空表示不改密码。 */
    @Size(min = 6, max = 32, message = "密码长度 6~32")
    private String password;

    @NotBlank(message = "昵称不能为空")
    @Size(max = 30, message = "昵称最长 30")
    private String nickname;

    @Email(message = "邮箱格式不正确")
    private String email;

    @Size(max = 20, message = "手机号最长 20")
    private String phone;

    private String departmentId;

    /** 1=启用，0=停用。 */
    private Integer status;

    /** 角色 ID 集合（当前公司内授权）。 */
    private List<String> roleIds;
}
