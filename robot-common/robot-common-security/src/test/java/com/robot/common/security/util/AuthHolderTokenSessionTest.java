package com.robot.common.security.util;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.context.SaTokenContext;
import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import cn.dev33.satoken.spring.SaTokenContextForSpringInJakartaServlet;
import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.StpUtil;
import com.robot.common.security.context.LoginUser;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.*;

/** 真正的 Sa-Token 内存 DAO + Servlet request，防止测试桩掩盖账户 Session 污染。 */
class AuthHolderTokenSessionTest {

    @Test
    void sameAccountTokensUseDifferentContextAndAccountSessionHasNoBusinessContext() {
        StpLogic originalLogic = StpUtil.getStpLogic();
        SaTokenContext originalContext = SaManager.getSaTokenContext();
        SaTokenDao originalDao = SaManager.getSaTokenDao();
        SaTokenDaoDefaultImpl memoryDao = new SaTokenDaoDefaultImpl();
        StpLogic logic = new StpLogic("auth-holder-context-test");
        logic.setConfig(new SaTokenConfig().setTokenName("Authorization").setTokenPrefix("Bearer")
                .setIsConcurrent(true).setIsShare(false).setIsReadCookie(false).setIsLog(false));
        try {
            SaManager.setSaTokenDao(memoryDao);
            SaManager.setSaTokenContext(new SaTokenContextForSpringInJakartaServlet());
            StpUtil.setStpLogic(logic);
            bind(null);
            StpUtil.login("same-user");
            String first = StpUtil.getTokenValue();
            AuthHolder.setSessionAttr(AuthHolder.SESSION_LOGIN_USER,
                    LoginUser.builder().userId("same-user").companyId("company-a").build());
            bind(null);
            StpUtil.login("same-user");
            String second = StpUtil.getTokenValue();
            AuthHolder.setSessionAttr(AuthHolder.SESSION_LOGIN_USER,
                    LoginUser.builder().userId("same-user").companyId("company-b").build());
            assertNotEquals(first, second);
            bind(first);
            assertEquals("company-a", AuthHolder.getSessionAttr(AuthHolder.SESSION_LOGIN_USER,
                    LoginUser.class).getCompanyId());
            assertNull(StpUtil.getSession().get(AuthHolder.SESSION_LOGIN_USER));
            bind(second);
            assertEquals("company-b", AuthHolder.getSessionAttr(AuthHolder.SESSION_LOGIN_USER,
                    LoginUser.class).getCompanyId());
            StpUtil.logoutByTokenValue(first);
            assertTrue(StpUtil.isLogin());
            assertEquals("company-b", AuthHolder.getSessionAttr(AuthHolder.SESSION_LOGIN_USER,
                    LoginUser.class).getCompanyId());
        } finally {
            RequestContextHolder.resetRequestAttributes();
            StpUtil.setStpLogic(originalLogic);
            SaManager.setSaTokenContext(originalContext);
            SaManager.setSaTokenDao(originalDao);
            memoryDao.destroy();
        }
    }

    private void bind(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (token != null) {
            request.addHeader("Authorization", "Bearer " + token);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request,
                new MockHttpServletResponse()));
    }
}
