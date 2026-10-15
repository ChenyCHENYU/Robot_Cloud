package com.robot.system.api.vo.account;

/**
 * 登录记录视图（/account/login-records；status: success|failed）。
 *
 * @param id 记录ID
 * @param time 时间显示值
 * @param ip 请求来源地址
 * @param location 位置显示值
 * @param device 设备类型显示值
 * @param browser 浏览器类型显示值
 * @param status 状态，1有效/0停用
 */
public record LoginRecordVO(
        String id,
        String time,
        String ip,
        String location,
        String device,
        String browser,
        String status) {}
