package com.robot.common.web.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.robot.common.core.domain.PageResult;
import com.robot.common.core.domain.Result;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 使用实际 Boot ObjectMapper 装配验证前端合同，避免只检查 customizer 字段。 */
class RobotWebJsonContractTest {
    private final WebApplicationContextRunner context=new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class,RobotWebAutoConfiguration.class));

    @Test
    void numericCountersAndLongTtlRemainNumbersAndOpaqueIdRemainsString() {
        context.run(application -> {
            ObjectMapper mapper=application.getBean(ObjectMapper.class);
            String id="900719925474099312345";
            JsonNode page=mapper.readTree(mapper.writeValueAsString(Result.success(
                    PageResult.of(List.of(new Row(id)),42L,2L,20L))));
            assertThat(page.path("code").isIntegralNumber()).isTrue();
            for(String field:List.of("total","page","pageSize"))
                assertThat(page.path("data").path(field).isIntegralNumber()).isTrue();
            assertThat(page.path("data").path("list").get(0).path("id").isTextual()).isTrue();
            assertThat(page.path("data").path("list").get(0).path("id").asText()).isEqualTo(id);
            JsonNode ttl=mapper.readTree(mapper.writeValueAsString(new TokenResponse(1800L,3L)));
            assertThat(ttl.path("expiresIn").isIntegralNumber()).isTrue();
            assertThat(ttl.path("count").isIntegralNumber()).isTrue();
            assertThat(ttl.path("expiresIn").asLong()).isEqualTo(1800);
        });
    }

    @Test
    void datetimeUsesDeclaredFormatAndUnknownFieldsRemainForwardCompatible() {
        context.run(application -> {
            ObjectMapper mapper=application.getBean(ObjectMapper.class);
            LocalDateTime time=LocalDateTime.of(2026,10,8,11,25,30);
            assertThat(mapper.readTree(mapper.writeValueAsString(new Timed(time))).path("createdAt").asText())
                    .isEqualTo("2026-10-08 11:25:30");
            assertThat(mapper.readValue("{\"createdAt\":\"2026-10-08 11:25:30\",\"future\":true}",Timed.class).createdAt())
                    .isEqualTo(time);
        });
    }

    record Row(String id) { }
    record TokenResponse(Long expiresIn,long count) { }
    record Timed(LocalDateTime createdAt) { }
}
