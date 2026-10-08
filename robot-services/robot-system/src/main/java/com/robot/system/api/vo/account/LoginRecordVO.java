package com.robot.system.api.vo.account;

/**
 * 登录记录视图（/account/login-records；status: success|failed）。
 */
public record LoginRecordVO(
        String id,
        String time,
        String ip,
        String location,
        String device,
        String browser,
        String status) {
}
