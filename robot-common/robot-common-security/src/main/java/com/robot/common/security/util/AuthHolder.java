package com.robot.common.security.util;

/**
 * Sa-Token 访问隔离点：业务代码禁止直接依赖 StpUtil，统一走本类，
 * 便于单测替换与未来替换底层认证组件。
 */
public final class AuthHolder {

    public static final String SESSION_LOGIN_USER = "robot:session:login-user";

    private static AuthAccessor accessor = new SaTokenAuthAccessor();

    private AuthHolder() {
    }

    /** 测试注入用（生产代码禁止调用）。 */
    static void replace(AuthAccessor replacement) {
        accessor = replacement;
    }

    /** 当前登录 ID（未登录 / Token 无效返回 null，不抛异常）。 */
    public static Object getLoginId() {
        return accessor.getLoginId();
    }

    /** 读取 Session 属性。 */
    public static <T> T getSessionAttr(String key, Class<T> type) {
        return accessor.getSessionAttr(key, type);
    }

    /** 写入 Session 属性。 */
    public static void setSessionAttr(String key, Object value) {
        accessor.setSessionAttr(key, value);
    }

    /** 登录用户权限码集合（由各服务 StpInterface 提供实现）。 */
    public static java.util.List<String> getPermissionList() {
        return accessor.getPermissionList();
    }

    /** 登录用户角色编码集合。 */
    public static java.util.List<String> getRoleList() {
        return accessor.getRoleList();
    }

    /** 访问器抽象。 */
    public interface AuthAccessor {
        Object getLoginId();

        <T> T getSessionAttr(String key, Class<T> type);

        void setSessionAttr(String key, Object value);

        java.util.List<String> getPermissionList();

        java.util.List<String> getRoleList();
    }

    /** 基于 Sa-Token 的默认实现。 */
    static class SaTokenAuthAccessor implements AuthAccessor {

        @Override
        public Object getLoginId() {
            try {
                Object loginId = cn.dev33.satoken.stp.StpUtil.getLoginIdDefaultNull();
                return loginId;
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getSessionAttr(String key, Class<T> type) {
            try {
                Object value = cn.dev33.satoken.stp.StpUtil.getSession()
                        .get(key);
                if (value == null) {
                    return null;
                }
                if (type.isInstance(value)) {
                    return (T) value;
                }
                return null;
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        public void setSessionAttr(String key, Object value) {
            cn.dev33.satoken.stp.StpUtil.getSession().set(key, value);
        }

        @Override
        public java.util.List<String> getPermissionList() {
            try {
                return cn.dev33.satoken.stp.StpUtil.getPermissionList();
            } catch (Exception e) {
                return java.util.Collections.emptyList();
            }
        }

        @Override
        public java.util.List<String> getRoleList() {
            try {
                return cn.dev33.satoken.stp.StpUtil.getRoleList();
            } catch (Exception e) {
                return java.util.Collections.emptyList();
            }
        }
    }
}
