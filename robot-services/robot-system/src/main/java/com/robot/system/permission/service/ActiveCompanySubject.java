package com.robot.system.permission.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.robot.common.core.constant.RobotConstants;
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

/** 当前账号/公司/租户/成员身份事实；角色解析和缓存命中均复用此实时校验。 */
@Component
@RequiredArgsConstructor
public class ActiveCompanySubject {
    private final SysUserCompanyMapper membershipMapper;
    private final SysUserMapper userMapper;
    private final SysCompanyMapper companyMapper;
    private final SysTenantMapper tenantMapper;

    /**
     * 当前授权事实实时查询，不能复用请求guard或上次授权快照。
     *
     * @param userId 平台账号ID
     * @param companyId 服务端确认的公司ID
     * @return 校验是否通过
     */
    public boolean isActive(String userId, String companyId) {
        return loadActive(userId, companyId, null) != null;
    }

    /**
     * 单次核验返回不可变安全快照，guard复用此结果而不重复读取用户/成员。
     *
     * @param userId 平台账号ID
     * @param companyId 服务端确认的公司ID
     * @param contextId 精确成员上下文ID
     * @return 当前事实派生的结果
     */
    public ActiveFacts loadActive(String userId, String companyId, String contextId) {
        if (isBlank(userId) || isBlank(companyId)) {
            return null;
        }
        SysUser user = userMapper.selectById(userId);
        SysCompany company = companyMapper.selectById(companyId);
        if (!matchesActiveUser(user, userId)
                || !matchesActiveCompany(company, companyId)
                || !hasActiveTenant(company.getTenantId())) {
            return null;
        }
        return loadMembershipFacts(user, companyId, contextId);
    }

    private ActiveFacts loadMembershipFacts(SysUser user, String companyId, String contextId) {
        String userId = user.getId();
        var members = activeMemberships(userId, companyId);
        if (members.size() != 1) {
            return null;
        }
        SysUserCompany member = members.get(0);
        if (contextId != null && !contextId.equals(member.getId())) {
            return null;
        }
        return new ActiveFacts(
                userId,
                companyId,
                member.getId(),
                user.getSecurityVersion(),
                member.getSecurityVersion(),
                user.getPasswordHash() == null
                        ? null
                        : com.robot.common.core.util.Digests.sha256Hex(user.getPasswordHash()));
    }

    /**
     * 已验证当前身份的不可变值，不包含密码摘要；fingerprint不能进入诊断日志。
     *
     * @param userId 平台账号ID
     * @param companyId 服务端确认的公司ID
     * @param contextId 精确成员上下文ID
     * @param accountVersion 当前账号撤销版本
     * @param membershipVersion 当前成员撤销版本
     * @param credentialFingerprint 会话绑定的凭据指纹，禁止日志
     */
    public record ActiveFacts(
            String userId,
            String companyId,
            String contextId,
            Long accountVersion,
            Long membershipVersion,
            String credentialFingerprint) {
        @Override
        public String toString() {
            return "ActiveFacts[verified]";
        }
    }

    private static boolean matchesActiveUser(SysUser user, String userId) {
        return user != null
                && userId.equals(user.getId())
                && active(user.getDeleteFlag(), user.getStatusFlag());
    }

    private static boolean matchesActiveCompany(SysCompany company, String companyId) {
        return company != null
                && companyId.equals(company.getId())
                && active(company.getDeleteFlag(), company.getStatusFlag())
                && !isBlank(company.getTenantId());
    }

    private boolean hasActiveTenant(String tenantId) {
        SysTenant tenant = tenantMapper.selectById(tenantId);
        return tenant != null
                && tenantId.equals(tenant.getId())
                && active(tenant.getDeleteFlag(), tenant.getStatusFlag());
    }

    private java.util.List<SysUserCompany> activeMemberships(String userId, String companyId) {
        return membershipMapper
                .selectList(
                        new LambdaQueryWrapper<SysUserCompany>()
                                .eq(SysUserCompany::getUserId, userId)
                                .eq(SysUserCompany::getCompanyId, companyId)
                                .eq(SysUserCompany::getStatusFlag, 1)
                                .eq(
                                        SysUserCompany::getDeleteFlag,
                                        RobotConstants.DELETE_FLAG_ACTIVE))
                .stream()
                .filter(
                        m ->
                                userId.equals(m.getUserId())
                                        && companyId.equals(m.getCompanyId())
                                        && active(m.getDeleteFlag(), m.getStatusFlag()))
                .toList();
    }

    private static boolean active(Integer deleted, Integer status) {
        return RobotConstants.DELETE_FLAG_ACTIVE.equals(deleted)
                && Integer.valueOf(1).equals(status);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
