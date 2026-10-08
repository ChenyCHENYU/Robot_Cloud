package com.robot.system.api.vo.account;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 个人资料视图（/account/profile；字段与前端 AccountProfile 契约一致）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AccountProfileVO(
        String username,
        String nickname,
        String email,
        String phone,
        String bio,
        String avatar,
        String role,
        String department,
        String createTime,
        String lastLoginTime,
        String lastLoginIp) {
}
