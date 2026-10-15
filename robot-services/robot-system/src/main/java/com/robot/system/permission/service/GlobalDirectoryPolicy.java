package com.robot.system.permission.service;

import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceAssert;
import com.robot.system.api.entity.SysUser;
import com.robot.system.user.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 全局目录写能力只来自独立平台账号事实和有效永久身份，不能从公司超管推导。 */
@Component
@RequiredArgsConstructor
public class GlobalDirectoryPolicy {
    private final PermissionGrantPolicy grantPolicy;
    private final SysUserMapper users;

    /** 创建或返回该类型的明确协作结果。 */
    public void requirePlatformAdministrator() {
        var operator = grantPolicy.requireOperator();
        SysUser account = users.selectById(operator.userId());
        ServiceAssert.isTrue(
                account != null
                        && operator.userId().equals(account.getId())
                        && Integer.valueOf(1).equals(account.getPlatformAdminFlag())
                        && Integer.valueOf(1).equals(account.getStatusFlag())
                        && Integer.valueOf(1).equals(account.getDeleteFlag()),
                ResultCode.FORBIDDEN,
                "全局目录仅允许有效永久平台管理员维护");
    }

    /**
     * 公司超管不能通过改密/资料或成员重绑接管独立平台属主。本人改密另有旧密码用例。
     *
     * @param target 审计目标
     */
    public void assertManagedAccount(SysUser target) {
        if (target != null && Integer.valueOf(1).equals(target.getPlatformAdminFlag())) {
            requirePlatformAdministrator();
        }
    }
}
