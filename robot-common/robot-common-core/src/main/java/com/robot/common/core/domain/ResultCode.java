package com.robot.common.core.domain;

/**
 * 错误码字典（接口契约的一部分，新增错误码必须在此登记）。
 *
 * <p>取值约束（与 Robot_Admin 前端 request-core 对齐）：
 * <ul>
 *   <li>成功码固定 {@code 0}（前端 {@code BUSINESS_SUCCESS_CODES = [200, 0, '200', '0']}，
 *       且 {@code /auth/context/activate} 强校验 {@code String(code) === '0'}，故统一取 0）；</li>
 *   <li>业务失败使用 4xxxx / 5xxxx，禁止与成功码 0 / 200 混用。</li>
 * </ul>
 */
public enum ResultCode {

    /** 成功。 */
    SUCCESS(0, "成功"),

    /** 通用业务失败（默认兜底，禁止滥用，优先使用语义化错误码）。 */
    FAILED(500, "系统繁忙，请稍后重试"),

    /** 未登录 / Token 无效或过期。 */
    UNAUTHORIZED(401, "登录状态已过期，请重新登录"),

    /** 已登录但无权限。 */
    FORBIDDEN(403, "没有访问权限，请联系管理员"),

    /** 资源不存在。 */
    NOT_FOUND(404, "请求的资源不存在"),

    /** 参数校验失败。 */
    PARAM_INVALID(400, "请求参数不合法"),

    /** 请求过于频繁（限流）。 */
    TOO_MANY_REQUESTS(429, "请求过于频繁，请稍后重试"),

    /* ==================== 认证域 401xx ==================== */
    AUTH_LOGIN_FAILED(40101, "账号或密码错误"),
    AUTH_ACCOUNT_DISABLED(40102, "账号已停用，请联系管理员"),
    AUTH_LOGIN_TICKET_INVALID(40103, "登录凭据无效或已过期，请重新登录"),
    AUTH_CONTEXT_DENIED(40104, "无权进入所选公司"),
    AUTH_REFRESH_TOKEN_INVALID(40105, "刷新令牌无效或已过期，请重新登录"),
    AUTH_COMPANY_MISSING(40106, "当前账号未关联公司，请联系企业管理员"),

    /* ==================== 用户域 402xx ==================== */
    USER_NOT_FOUND(40201, "用户不存在"),
    USER_USERNAME_DUPLICATED(40202, "用户名已存在"),
    USER_PASSWORD_WEAK(40203, "密码强度不足"),

    /* ==================== 角色域 403xx ==================== */
    ROLE_NOT_FOUND(40301, "角色不存在"),
    ROLE_CODE_DUPLICATED(40302, "角色编码已存在"),

    /* ==================== 菜单域 404xx ==================== */
    MENU_NOT_FOUND(40401, "菜单不存在"),
    MENU_HAS_CHILDREN(40402, "存在子菜单，禁止删除"),

    /* ==================== 部门域 405xx ==================== */
    DEPT_NOT_FOUND(40501, "部门不存在"),
    DEPT_HAS_CHILDREN(40502, "存在子部门，禁止删除"),

    /* ==================== 字典域 406xx ==================== */
    DICT_NOT_FOUND(40601, "字典不存在"),
    DICT_TYPE_DUPLICATED(40602, "字典类型已存在"),

    /* ==================== 通用数据域 409xx ==================== */
    DATA_DUPLICATED(40901, "数据已存在，请勿重复提交"),
    DATA_CONCURRENT_CONFLICT(40902, "数据已被他人修改，请刷新后重试"),
    DATA_REFERENCED(40903, "数据已被引用，禁止删除");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
