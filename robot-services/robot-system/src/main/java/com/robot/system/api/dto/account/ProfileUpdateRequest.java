package com.robot.system.api.dto.account;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;

/** 自助资料白名单；身份、公司、权限和治理字段不接收客户端赋值。 */
@Data
public class ProfileUpdateRequest {
    @Pattern(regexp = "(?s).*\\S.*", message = "昵称不可为空白")
    @Size(max = 30)
    private String nickname;

    @Email
    @Size(max = 100)
    @ToString.Exclude
    private String email;

    @Size(max = 20)
    @ToString.Exclude
    private String phone;

    @JsonIgnore private boolean nicknamePresent;
    @JsonIgnore @ToString.Exclude private boolean emailPresent;
    @JsonIgnore @ToString.Exclude private boolean phonePresent;

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
}
