package com.robot.system.api.dto.user;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 管理员改密命令；分别校验账号revision和当前公司membershipRevision，禁止日志记录密码。
 *
 * @author Robot Cloud
 * @since 1.0.0-SNAPSHOT
 */
public record UserPasswordResetRequest(
        @NotBlank
        @Size(min = 6, max = 32)
        String password,
        @NotNull
        @Min(0)
        Integer expectedRevision,
        @NotNull
        @Min(0)
        Integer expectedMembershipRevision) {

    @Override
    public String toString() {
        return "UserPasswordResetRequest[password=[REDACTED], expectedRevision=" + expectedRevision
                + ", expectedMembershipRevision=" + expectedMembershipRevision + "]";
    }
}
