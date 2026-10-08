package com.robot.auth.authn.dto;

import java.util.List;

/**
 * 登录前公司发现响应（前端 getLoginCompanies 契约：{ companies: [...] }）。
 */
public record LoginCompaniesResponse(
        List<LoginCompany> companies) {

    /** 公司项：id=上下文 ID，isPrimary=是否主公司。 */
    public record LoginCompany(
            String id,
            boolean isPrimary,
            String tenantName,
            String companyName) {
    }
}
