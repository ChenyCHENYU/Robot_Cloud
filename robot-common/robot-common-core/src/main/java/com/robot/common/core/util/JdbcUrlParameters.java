package com.robot.common.core.util;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

/** Strict MySQL policy parameter lookup; ambiguous settings fail closed without exposing URLs. */
public final class JdbcUrlParameters {
    private JdbcUrlParameters() {}

    /**
     * Find one decoded parameter with its exact documented spelling.
     *
     * @param jdbc configured MySQL JDBC URL
     * @param key exact policy parameter name
     * @return value only when the URL and this parameter are unambiguous
     */
    public static Optional<String> uniqueValue(String jdbc, String key) {
        if (!validQuery(jdbc, key)) {
            return Optional.empty();
        }
        try {
            var matches =
                    Arrays.stream(jdbc.substring(jdbc.indexOf('?') + 1).split("&", -1))
                            .map(JdbcUrlParameters::decodeParameter)
                            .filter(parameter -> key.equalsIgnoreCase(parameter.name()))
                            .toList();
            if (matches.size() != 1) {
                return Optional.empty();
            }
            Parameter selected = matches.get(0);
            return key.equals(selected.name())
                    ? Optional.ofNullable(selected.value())
                    : Optional.empty();
        } catch (IllegalArgumentException malformedEncoding) {
            return Optional.empty();
        }
    }

    private static boolean validQuery(String jdbc, String key) {
        return jdbc != null
                && key != null
                && !key.isBlank()
                && jdbc.startsWith("jdbc:mysql:")
                && jdbc.indexOf('?') >= 0
                && jdbc.indexOf('#') < 0;
    }

    private static Parameter decodeParameter(String parameter) {
        String[] pair = parameter.split("=", 2);
        String name = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
        String value = pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : null;
        return new Parameter(name, value);
    }

    private record Parameter(String name, String value) {
        @Override
        public String toString() {
            return "JdbcParameter[redacted]";
        }
    }
}
