package com.robot.auth.authn.dto;

import java.util.List;

/**
 * 登录前公司发现响应（前端 getLoginCompanies 契约：{ companies: [...] }）。
 *
 * @param companies 可选择的有效公司清单
 */
public record LoginCompaniesResponse(List<LoginCompany> companies) {
    /**
     * Canonical response detaches the caller's company collection.
     *
     * @param companies nonnull available company list
     */
    public LoginCompaniesResponse {
        companies = List.copyOf(companies);
    }

    /**
     * 公司项：id=上下文 ID，isPrimary=是否主公司。
     *
     * @param id 有效成员上下文ID
     * @param isPrimary 是否唯一主公司
     * @param tenantName 租户显示名
     * @param companyName 公司显示名
     */
    public record LoginCompany(
            String id, boolean isPrimary, String tenantName, String companyName) {}
}
