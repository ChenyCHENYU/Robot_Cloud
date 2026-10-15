package com.robot.common.core.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Shared path and exact-single-header predicates for servlet and gateway production management
 * boundaries.
 */
public final class ManagementEndpointPolicy {
    private ManagementEndpointPolicy() {}

    /**
     * Identifies OpenAPI and its UI surfaces for production blocking.
     *
     * @param path normalized request path
     * @return whether the request targets API documentation
     */
    public static boolean documentation(String path) {
        return path.startsWith("/v3/api-docs")
                || path.equals("/swagger-ui.html")
                || path.startsWith("/swagger-ui/")
                || path.equals("/doc.html")
                || path.startsWith("/webjars/");
    }

    /**
     * Keeps non-health management endpoints behind the independent management credential.
     *
     * @param path normalized request path
     * @return whether an independent management credential is required
     */
    public static boolean protectedManagement(String path) {
        return path.startsWith("/actuator")
                && !path.equals("/actuator/health")
                && !path.startsWith("/actuator/health/");
    }

    /**
     * Accepts exactly one header matching the separately configured management credential.
     *
     * @param headers submitted header values
     * @param secret configured management credential
     * @return whether a complete, unambiguous credential matches in constant time
     */
    public static boolean authorized(List<String> headers, String secret) {
        if (headers == null
                || headers.size() != 1
                || secret == null
                || secret.isBlank()
                || headers.get(0) == null) {
            return false;
        }
        return MessageDigest.isEqual(
                secret.getBytes(StandardCharsets.UTF_8),
                headers.get(0).getBytes(StandardCharsets.UTF_8));
    }
}
