package com.robot.common.core.util;

import java.util.UUID;
import java.util.regex.Pattern;

/** 网关与 Servlet 服务共用的链路标识边界，避免外部头携带换行、URL 或无限长日志字段。 */
public final class TraceIds {
    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private TraceIds() {
    }

    /** 保留合法既有标识；缺失或格式非法时生成新的 32 位十六进制标识，不回显原输入。 */
    public static String resolve(String candidate) {
        if (candidate != null && VALID_ID.matcher(candidate).matches()) {
            return candidate;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }
}
