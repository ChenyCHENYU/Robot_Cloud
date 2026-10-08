package com.robot.system.api.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.robot.common.mybatis.domain.TenantEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 登录日志（认证服务经内部接口写入；只追加）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_login_log")
public class SysLoginLog extends TenantEntity {

    private String userId;

    private String username;

    /** login / activate / switch / refresh / logout。 */
    private String loginType;

    /** 结果：1=成功，0=失败。 */
    private Integer statusFlag;

    private String ip;

    private String userAgent;

    private LocalDateTime loginTime;
}
