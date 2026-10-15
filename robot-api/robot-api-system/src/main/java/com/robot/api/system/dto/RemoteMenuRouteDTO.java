package com.robot.api.system.dto;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 前端动态路由节点（/auth/menu-list 返回 data 的元素结构，字段与 Robot_Admin DynamicRoute 契约一一对应：path / name /
 * component / redirect / meta / children）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class RemoteMenuRouteDTO implements Serializable {

    @Serial private static final long serialVersionUID = 1L;

    private String path;

    private String name;

    private String component;

    private String redirect;

    private RouteMetaDTO meta;

    private List<RemoteMenuRouteDTO> children;

    /**
     * 返回独立展示元数据，调用方不能改写已返回的路由事实。
     *
     * @return 元数据副本或 null
     */
    public RouteMetaDTO getMeta() {
        return copyMeta(meta);
    }

    /**
     * 保存独立元数据，保持 Jackson 可写 Bean 契约。
     *
     * @param meta 前端展示元数据
     */
    public void setMeta(RouteMetaDTO meta) {
        this.meta = copyMeta(meta);
    }

    /**
     * 返回独立节点树，集合及子节点均不暴露内部引用。
     *
     * @return 独立节点树或 null
     */
    public List<RemoteMenuRouteDTO> getChildren() {
        return copyChildren(children);
    }

    /**
     * 保存独立节点树，避免输入方的后续变更污染路由事实。
     *
     * @param children 子路由树
     */
    public void setChildren(List<RemoteMenuRouteDTO> children) {
        this.children = copyChildren(children);
    }

    private static RouteMetaDTO copyMeta(RouteMetaDTO source) {
        if (source == null) {
            return null;
        }
        return RouteMetaDTO.builder()
                .title(source.title)
                .requiresAuth(source.requiresAuth)
                .roles(source.roles)
                .icon(source.icon)
                .sortOrder(source.sortOrder)
                .keepAlive(source.keepAlive)
                .hideMenu(source.hideMenu)
                .build();
    }

    private static List<RemoteMenuRouteDTO> copyChildren(List<RemoteMenuRouteDTO> source) {
        if (source == null) {
            return null;
        }
        List<RemoteMenuRouteDTO> result = new ArrayList<>(source.size());
        source.forEach(node -> result.add(copyNode(node)));
        return result;
    }

    private static RemoteMenuRouteDTO copyNode(RemoteMenuRouteDTO source) {
        if (source == null) {
            return null;
        }
        RemoteMenuRouteDTO copy = new RemoteMenuRouteDTO();
        copy.path = source.path;
        copy.name = source.name;
        copy.component = source.component;
        copy.redirect = source.redirect;
        copy.meta = copyMeta(source.meta);
        copy.children = copyChildren(source.children);
        return copy;
    }

    /** Builder 对可变输入执行相同的独立快照策略。 */
    public static class RemoteMenuRouteDTOBuilder {
        /**
         * 设置独立展示元数据。
         *
         * @param value 展示元数据
         * @return 当前 Builder
         */
        public RemoteMenuRouteDTOBuilder meta(RouteMetaDTO value) {
            this.meta = copyMeta(value);
            return this;
        }
        /**
         * 设置独立路由节点树。
         *
         * @param value 子路由树
         * @return 当前 Builder
         */
        public RemoteMenuRouteDTOBuilder children(List<RemoteMenuRouteDTO> value) {
            this.children = copyChildren(value);
            return this;
        }
    }

    /** 前端展示元数据，角色集合保持独立输入与输出。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    public static class RouteMetaDTO implements Serializable {
        @Serial private static final long serialVersionUID = 1L;
        private String title;
        private Boolean requiresAuth;
        private List<String> roles;
        private String icon;
        private Integer sortOrder;
        private Boolean keepAlive;
        private Boolean hideMenu;

        /**
         * 返回独立角色集合。
         *
         * @return 角色集合副本或 null
         */
        public List<String> getRoles() {
            return roles == null ? null : new ArrayList<>(roles);
        }
        /**
         * 保存独立角色集合。
         *
         * @param roles 展示角色标识集合
         */
        public void setRoles(List<String> roles) {
            this.roles = roles == null ? null : new ArrayList<>(roles);
        }

        /** 展示元数据 Builder 对集合输入执行快照策略。 */
        public static class RouteMetaDTOBuilder {
            /**
             * 设置独立角色集合。
             *
             * @param value 展示角色标识集合
             * @return 当前 Builder
             */
            public RouteMetaDTOBuilder roles(List<String> value) {
                this.roles = value == null ? null : new ArrayList<>(value);
                return this;
            }
        }
    }
}
