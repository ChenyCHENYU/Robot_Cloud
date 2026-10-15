package com.robot.common.core.constant;

/**
 * 全局常量。
 *
 * <p>命名与语义对齐 wl-skills-bd 通用要求：
 *
 * <ul>
 *   <li>租户列 {@code COMPANY_ID}，取值只来自 {@code AuthUtil.getLoginCompanyId()}，禁止取自请求；
 *   <li>软删列 {@code DELETE_FLAG}：1=有效，0=删除（受管 profile 语义，禁止反向使用）；
 *   <li>乐观锁列 {@code REVISION}，初始值 0；
 *   <li>数据库列禁止 {@code is_} 前缀，布尔/标志统一 {@code *_FLAG}，Java 属性 {@code xxxFlag}。
 * </ul>
 */
public final class RobotConstants {

    private RobotConstants() {}

    /** 请求头：认证令牌（前端 request-core 约定 Authorization: Bearer xxx）。 */
    public static final String HEADER_AUTHORIZATION = "Authorization";

    /** Bearer 前缀。 */
    public static final String TOKEN_PREFIX = "Bearer ";

    /** 请求头：链路追踪 ID（网关生成，向下透传）。 */
    public static final String HEADER_TRACE_ID = "X-Trace-Id";

    /** 请求头：内部服务调用标识（网关清洗，服务侧校验）。 */
    public static final String HEADER_INNER_CALL = "X-Inner-Call";

    /** 内部委托用户 ID（仅 /inner/** 生效；网关清洗外部伪造值）。 */
    public static final String HEADER_INNER_USER = "X-Inner-User-Id";

    /** 内部委托公司 ID（仅 /inner/** 生效；网关清洗外部伪造值）。 */
    public static final String HEADER_INNER_COMPANY = "X-Inner-Company-Id";

    /** 内部委托用户名（审计操作人用；仅 /inner/** 生效）。 */
    public static final String HEADER_INNER_USERNAME = "X-Inner-Username";

    /** 租户列名（MySQL 统一小写）。 */
    public static final String TENANT_COLUMN = "company_id";

    /** 租户 Java 字段名。 */
    public static final String TENANT_FIELD = "companyId";

    /** 软删列名。 */
    public static final String DELETE_COLUMN = "delete_flag";

    /** 有效数据标记。 */
    public static final Integer DELETE_FLAG_ACTIVE = 1;

    /** 已删除标记。 */
    public static final Integer DELETE_FLAG_DELETED = 0;

    /** 乐观锁列名 / Java 字段。 */
    public static final String REVISION_COLUMN = "revision";

    public static final String REVISION_FIELD = "revision";

    /** 超级管理员角色编码（拥有全部权限，跳过权限码匹配）。 */
    public static final String SUPER_ADMIN_ROLE_CODE = "SUPER_ADMIN";

    /** 分页参数默认值与上限（防恶意大页拖库）。 */
    public static final int DEFAULT_PAGE = 1;

    public static final int DEFAULT_PAGE_SIZE = 10;
    public static final int MAX_PAGE_SIZE = 200;

    /** 批量写入单批上限（saveBatch ≤ 1000，wl-skills-bd B 系数据安全护栏）。 */
    public static final int BATCH_WRITE_LIMIT = 1000;

    /** Redis Key 根前缀：robot:{env}:...，多环境共池防互踩。 */
    public static final String REDIS_KEY_PREFIX = "robot";
}
