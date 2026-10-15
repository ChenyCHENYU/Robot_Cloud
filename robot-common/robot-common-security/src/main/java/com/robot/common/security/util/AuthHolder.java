package com.robot.common.security.util;

/** Sa-Token 访问隔离点：业务代码禁止直接依赖 StpUtil，统一走本类， 便于单测替换与未来替换底层认证组件。 */
public final class AuthHolder {

    public static final String SESSION_LOGIN_USER = "robot:session:login-user";

    /** 独立 token 会话所属刷新族；业务服务可据此复验会话是否已撤销。 */
    public static final String SESSION_AUTH_FAMILY = "robot:session:auth-family";

    /** 当前访问令牌的 SHA-256，用于与刷新族的当前访问令牌核对。 */
    public static final String SESSION_AUTH_ACCESS_DIGEST = "robot:session:access-digest";

    /** 登录时的凭据指纹；只保存在服务端，改密后不得继续刷新旧会话。 */
    public static final String SESSION_CREDENTIAL_FINGERPRINT =
            "robot:session:credential-fingerprint";

    private static AuthAccessor accessor = new SaTokenAuthAccessor();

    private AuthHolder() {}

    /** 测试注入用（生产代码禁止调用）。 */
    static void replace(AuthAccessor replacement) {
        accessor = replacement;
    }

    /**
     * 当前登录 ID（未登录 / Token 无效返回 null，不抛异常）。
     *
     * @return 当前访问令牌对应账号ID或null
     */
    public static Object getLoginId() {
        return accessor.getLoginId();
    }

    /**
     * 读取当前token Session的属性。
     *
     * @param <T> 所需视图类型
     * @param key 固定服务端Session字段
     * @param type 所需视图类型
     * @return 匹配类型的属性或null
     */
    public static <T> T getSessionAttr(String key, Class<T> type) {
        return accessor.getSessionAttr(key, type);
    }

    /**
     * 写入当前token Session的服务端属性。
     *
     * @param key 固定服务端Session字段
     * @param value 已验证的服务端属性
     */
    public static void setSessionAttr(String key, Object value) {
        accessor.setSessionAttr(key, value);
    }

    /**
     * 登录用户权限码集合（由各服务 StpInterface 提供实现）。
     *
     * @return 当前用户有效权限码
     */
    public static java.util.List<String> getPermissionList() {
        return accessor.getPermissionList();
    }

    /**
     * 登录用户角色编码集合。
     *
     * @return 当前公司有效角色码
     */
    public static java.util.List<String> getRoleList() {
        return accessor.getRoleList();
    }

    /** 访问器抽象。 */
    public interface AuthAccessor {
        /**
         * Read the current access identity.
         *
         * @return current account ID or null
         */
        Object getLoginId();

        /**
         * Read an attribute only as its expected server type.
         *
         * @param <T> expected attribute type
         * @param key fixed server attribute
         * @param type expected runtime type
         * @return matching attribute or null
         */
        <T> T getSessionAttr(String key, Class<T> type);

        /**
         * Save one verified server attribute.
         *
         * @param key fixed server attribute
         * @param value verified server value
         */
        void setSessionAttr(String key, Object value);

        /**
         * Read the current company's effective permission facts.
         *
         * @return effective permissions
         */
        java.util.List<String> getPermissionList();

        /**
         * Read the current company's effective roles.
         *
         * @return effective role codes
         */
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
                Object value = cn.dev33.satoken.stp.StpUtil.getTokenSession().get(key);
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
            cn.dev33.satoken.stp.StpUtil.getTokenSession().set(key, value);
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
