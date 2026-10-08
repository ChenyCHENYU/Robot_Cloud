package com.robot.api.system.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 前端动态路由节点（/auth/menu-list 返回 data 的元素结构，字段与 Robot_Admin
 * DynamicRoute 契约一一对应：path / name / component / redirect / meta / children）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RemoteMenuRouteDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String path;

    private String name;

    private String component;

    private String redirect;

    private RouteMetaDTO meta;

    private List<RemoteMenuRouteDTO> children;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RouteMetaDTO implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String title;
        private Boolean requiresAuth;
        private List<String> roles;
        private String icon;
        private Integer sortOrder;
        private Boolean keepAlive;
        private Boolean hideMenu;
    }
}
