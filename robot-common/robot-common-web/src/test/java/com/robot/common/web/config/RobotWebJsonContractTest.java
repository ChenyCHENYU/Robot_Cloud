package com.robot.common.web.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.robot.common.core.domain.PageResult;
import com.robot.common.core.domain.Result;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson2.autoconfigure.Jackson2AutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

/** 使用实际 Boot ObjectMapper 装配验证前端合同，避免只检查 customizer 字段。 */
class RobotWebJsonContractTest {
    private static final long MATCHING_ROWS = 42L;
    private static final long PAGE_SIZE = 20L;
    private static final long TOKEN_TTL_SECONDS = 1800L;
    private static final long TOKEN_COUNT = 3L;

    private final WebApplicationContextRunner context =
            new WebApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(
                                    Jackson2AutoConfiguration.class,
                                    RobotWebAutoConfiguration.class));

    @Test
    void numericCountersAndLongTtlRemainNumbersAndOpaqueIdRemainsString() {
        context.run(
                application -> {
                    ObjectMapper mapper = application.getBean(ObjectMapper.class);
                    String id = "900719925474099312345";
                    JsonNode page =
                            mapper.readTree(
                                    mapper.writeValueAsString(
                                            Result.success(
                                                    PageResult.of(
                                                            List.of(new Row(id)),
                                                            MATCHING_ROWS,
                                                            2L,
                                                            PAGE_SIZE))));
                    assertThat(page.path("code").isIntegralNumber()).isTrue();
                    for (String field : List.of("total", "page", "pageSize")) {
                        assertThat(page.path("data").path(field).isIntegralNumber()).isTrue();
                    }
                    assertThat(page.path("data").path("list").get(0).path("id").isTextual())
                            .isTrue();
                    assertThat(page.path("data").path("list").get(0).path("id").asText())
                            .isEqualTo(id);
                    JsonNode ttl =
                            mapper.readTree(
                                    mapper.writeValueAsString(
                                            new TokenResponse(TOKEN_TTL_SECONDS, TOKEN_COUNT)));
                    assertThat(ttl.path("expiresIn").isIntegralNumber()).isTrue();
                    assertThat(ttl.path("count").isIntegralNumber()).isTrue();
                    assertThat(ttl.path("expiresIn").asLong()).isEqualTo(TOKEN_TTL_SECONDS);
                });
    }

    @Test
    void datetimeUsesDeclaredFormatAndUnknownFieldsRemainForwardCompatible() {
        context.run(
                application -> {
                    ObjectMapper mapper = application.getBean(ObjectMapper.class);
                    LocalDateTime time = LocalDateTime.parse("2026-10-08T11:25:30");
                    assertThat(
                                    mapper.readTree(mapper.writeValueAsString(new Timed(time)))
                                            .path("createdAt")
                                            .asText())
                            .isEqualTo("2026-10-08 11:25:30");
                    assertThat(
                                    mapper.readValue(
                                                    "{\"createdAt\":\"2026-10-08"
                                                            + " 11:25:30\",\"future\":true}",
                                                    Timed.class)
                                            .createdAt())
                            .isEqualTo(time);
                });
    }

    record Row(String id) {}

    record TokenResponse(Long expiresIn, long count) {}

    record Timed(LocalDateTime createdAt) {}

    @ParameterizedTest
    @ValueSource(
            strings = {
                "2026-02-29 12:00:00",
                "2026-04-31 12:00:00",
                "2026-10-08 24:00:00",
                "2026-10-08T11:25:30",
                "2026-10-08 11:25:30+08:00"
            })
    void invalidCalendarOrAmbiguousWireTimeIsRejected(String input) {
        context.run(
                application -> {
                    ObjectMapper mapper = application.getBean(ObjectMapper.class);
                    String json = mapper.writeValueAsString(java.util.Map.of("createdAt", input));
                    assertThatThrownBy(() -> mapper.readValue(json, Timed.class))
                            .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
                });
    }
}
