package com.robot.system.api.vo.account;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 个人资料视图（/account/profile；字段与前端 AccountProfile 契约一致）。
 *
 * @param username 平台账号登录名
 * @param nickname 昵称
 * @param email 邮箱资料
 * @param phone 电话资料
 * @param bio 个人简介
 * @param avatar 头像地址
 * @param role 已校验的公司角色事实
 * @param department 当前成员主部门
 * @param createTime 创建时间显示值
 * @param lastLoginTime 最近成功登录时间显示值
 * @param lastLoginIp 最近成功登录来源地址
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
        String lastLoginIp) {}
