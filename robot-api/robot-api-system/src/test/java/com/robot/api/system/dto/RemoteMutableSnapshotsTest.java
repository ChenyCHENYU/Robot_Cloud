package com.robot.api.system.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 跨服务可写 DTO 的输入、输出与 JSON 快照独立，字段合同保持原样。 */
class RemoteMutableSnapshotsTest {
    @Test
    void routeBuilderAndBeanSnapshotsDoNotShareMetadataOrChildren() throws Exception {
        var child = RemoteMenuRouteDTO.builder().path("/child").name("robot-menu:child").build();
        var children = new ArrayList<>(List.of(child));
        var roles = new ArrayList<>(List.of("reader"));
        var meta = RemoteMenuRouteDTO.RouteMetaDTO.builder().title("original").roles(roles).build();
        var route =
                RemoteMenuRouteDTO.builder().path("/root").meta(meta).children(children).build();
        child.setName("changed");
        children.clear();
        roles.clear();
        meta.setTitle("changed");
        assertThat(route.getMeta().getTitle()).isEqualTo("original");
        assertThat(route.getMeta().getRoles()).containsExactly("reader");
        assertThat(route.getChildren())
                .extracting(RemoteMenuRouteDTO::getName)
                .containsExactly("robot-menu:child");
        route.getMeta().setTitle("external");
        route.getChildren().get(0).setName("external");
        assertThat(route.getMeta().getTitle()).isEqualTo("original");
        assertThat(route.getChildren().get(0).getName()).isEqualTo("robot-menu:child");
        var mapper = new ObjectMapper();
        var restored = mapper.readValue(mapper.writeValueAsString(route), RemoteMenuRouteDTO.class);
        assertThat(restored).isEqualTo(route);
        var setter = new RemoteMenuRouteDTO();
        setter.setMeta(meta);
        setter.setChildren(List.of(child));
        meta.setTitle("later");
        child.setName("later");
        assertThat(setter.getMeta().getTitle()).isEqualTo("changed");
        assertThat(setter.getChildren().get(0).getName()).isEqualTo("changed");
    }

    @Test
    void contextRoleSnapshotsRetainDepartmentAndSecurityFactsDuringJsonRoundtrip()
            throws Exception {
        var role =
                RemoteUserContextDTO.RoleItem.builder()
                        .id("role")
                        .code("reader")
                        .name("Reader")
                        .build();
        var roles = new ArrayList<>(List.of(role));
        var context =
                RemoteUserContextDTO.builder()
                        .contextId("member")
                        .departmentId("department")
                        .securityVersion(1L)
                        .roles(roles)
                        .build();
        roles.clear();
        role.setCode("altered");
        assertThat(context.getRoles().get(0).getCode()).isEqualTo("reader");
        context.getRoles().get(0).setCode("external");
        assertThat(context.getRoles().get(0).getCode()).isEqualTo("reader");
        var mapper = new ObjectMapper();
        var restored =
                mapper.readValue(mapper.writeValueAsString(context), RemoteUserContextDTO.class);
        assertThat(restored.getDepartmentId()).isEqualTo("department");
        assertThat(restored.getSecurityVersion()).isEqualTo(1L);
        assertThat(restored.getRoles().get(0).getCode()).isEqualTo("reader");
        var setter = new RemoteUserContextDTO();
        setter.setRoles(List.of(role));
        role.setCode("later");
        assertThat(setter.getRoles().get(0).getCode()).isEqualTo("altered");
    }
}
