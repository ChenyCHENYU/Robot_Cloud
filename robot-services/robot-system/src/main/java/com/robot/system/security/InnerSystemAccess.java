package com.robot.system.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.robot.common.core.constant.RobotConstants;
import com.robot.common.security.context.InnerTrustContext;
import com.robot.common.security.exception.InnerAccessDeniedException;
import com.robot.common.security.util.AuthUtil;
import com.robot.system.api.entity.SysCompany;
import com.robot.system.api.entity.SysTenant;
import com.robot.system.api.entity.SysUser;
import com.robot.system.api.entity.SysUserCompany;
import com.robot.system.mapper.SysCompanyMapper;
import com.robot.system.mapper.SysTenantMapper;
import com.robot.system.user.mapper.SysUserCompanyMapper;
import com.robot.system.user.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** 内部读写入口的服务身份、参数身份与实际有效成员边界。 */
@Component
@RequiredArgsConstructor
public class InnerSystemAccess {
    private final SysUserMapper userMapper;
    private final SysUserCompanyMapper userCompanyMapper;
    private final SysCompanyMapper companyMapper;
    private final SysTenantMapper tenantMapper;

    /** 登录前可按账号查资料；带委托时只允许访问当前账号。 */
    public void requireUser(String userId) {
        InnerTrustContext context = AuthUtil.requireTrustedService();
        if (context.hasDelegate() && !Objects.equals(userId, AuthUtil.getLoginUserId())) {
            throw new InnerAccessDeniedException();
        }
    }

    /** 权限供给必须有完整委托，且成员/用户/公司/租户当前均有效。 */
    public void requireActiveMembership(String userId, String companyId) {
        AuthUtil.requireTrustedUser(userId, companyId);
        SysUser user = userMapper.selectById(userId);
        SysCompany company = companyMapper.selectById(companyId);
        if (user == null || !active(user.getDeleteFlag(), user.getStatusFlag())
                || company == null || !active(company.getDeleteFlag(), company.getStatusFlag())) {
            throw new InnerAccessDeniedException();
        }
        if (company.getTenantId() == null || company.getTenantId().isBlank()) {
            throw new InnerAccessDeniedException();
        }
        SysTenant tenant = tenantMapper.selectById(company.getTenantId());
        if (tenant == null || !active(tenant.getDeleteFlag(), tenant.getStatusFlag())
                || userCompanyMapper.selectCount(new LambdaQueryWrapper<SysUserCompany>()
                .eq(SysUserCompany::getUserId, userId)
                .eq(SysUserCompany::getCompanyId, companyId)
                .eq(SysUserCompany::getStatusFlag, 1)
                .eq(SysUserCompany::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE)) == 0) {
            throw new InnerAccessDeniedException();
        }
    }

    public static boolean active(Integer deleteFlag, Integer statusFlag) {
        return Objects.equals(deleteFlag, RobotConstants.DELETE_FLAG_ACTIVE)
                && Objects.equals(statusFlag, 1);
    }
}
