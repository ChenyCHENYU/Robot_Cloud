package com.robot.system.api.dto.user;

import com.robot.common.web.validation.ValidNewPassword;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 管理员改密命令；分别校验账号revision和当前公司membershipRevision，禁止日志记录密码。
 *
 * @author Robot Cloud
 * @since 1.0.0-SNAPSHOT
 * @param password password协作输入
 * @param expectedRevision 客户端期望的聚合版本
 * @param expectedMembershipRevision expectedMembershipRevision协作输入
 */
public record UserPasswordResetRequest(
        @NotBlank @Size(min = 6, max = 32) @ValidNewPassword String password,
        @NotNull @Min(0) Integer expectedRevision,
        @NotNull @Min(0) Integer expectedMembershipRevision) {

    @Override
    public String toString() {
        return "UserPasswordResetRequest[password=[REDACTED], expectedRevision="
                + expectedRevision
                + ", expectedMembershipRevision="
                + expectedMembershipRevision
                + "]";
    }
}
