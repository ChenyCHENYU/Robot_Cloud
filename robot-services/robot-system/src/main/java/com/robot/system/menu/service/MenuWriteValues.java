package com.robot.system.menu.service;

import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceAssert;
import com.robot.system.api.dto.menu.MenuButtonSaveRequest;
import com.robot.system.api.dto.menu.MenuSaveRequest;
import com.robot.system.api.entity.SysMenu;
import com.robot.system.api.entity.SysMenuButton;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** 菜单/按钮白名单值规则与实体赋值；引用检查、CAS和审计仍由写服务编排。 */
final class MenuWriteValues {
    private MenuWriteValues() {}

    static void validateMenu(MenuSaveRequest request) {
        ServiceAssert.notBlank(request.getName(), ResultCode.PARAM_INVALID);
        ServiceAssert.isTrue(
                Set.of("directory", "menu").contains(Objects.toString(request.getType(), ""))
                        && request.isFlagsConsistent(),
                ResultCode.PARAM_INVALID);
        validateRoutePath(request.getPath());
        validatePageComponent(request);
        if (request.getPermission() != null && !request.getPermission().isBlank()) {
            exactPermission(request.getPermission());
        }
    }

    private static void validateRoutePath(String path) {
        ServiceAssert.isTrue(
                path != null
                        && !path.isBlank()
                        && !path.matches(".*[\\s?#].*")
                        && Arrays.stream(path.split("/"))
                                .noneMatch(segment -> ".".equals(segment) || "..".equals(segment))
                        && !path.toLowerCase(Locale.ROOT).matches("^(javascript|data):.*"),
                ResultCode.PARAM_INVALID,
                "菜单路由路径不合法");
    }

    private static void validatePageComponent(MenuSaveRequest request) {
        if ("menu".equals(request.getType())) {
            ServiceAssert.isTrue(
                    request.getComponent() != null
                            && request.getComponent().matches("/?[\\w./-]+")
                            && !request.getComponent().contains(".."),
                    ResultCode.PARAM_INVALID,
                    "页面组件路径不合法");
        }
    }

    static void validateButtonFields(MenuButtonSaveRequest request) {
        ServiceAssert.notBlank(request.getName(), ResultCode.PARAM_INVALID);
        exactPermission(request.getPermission());
    }

    private static void exactPermission(String code) {
        ServiceAssert.isTrue(
                code != null && code.matches("[\\w.-]+(?::[\\w.-]+)+"),
                ResultCode.PARAM_INVALID,
                "权限标识必须是精确资源动作");
    }

    static void apply(SysMenu menu, MenuSaveRequest request) {
        menu.setMenuName(request.getName());
        menu.setMenuType(request.getType());
        menu.setPath(request.getPath());
        menu.setComponent(request.getComponent());
        menu.setRedirect(request.getRedirect());
        menu.setPermCode(request.getPermission());
        menu.setIcon(request.getIcon());
        menu.setSortNo(request.getSort() == null ? Integer.valueOf(0) : request.getSort());
        menu.setVisibleFlag(request.effectiveVisibleFlag());
        menu.setKeepAliveFlag(request.effectiveKeepAliveFlag());
        menu.setStatusFlag(request.getStatus() == null ? Integer.valueOf(1) : request.getStatus());
        menu.setRemark(request.getRemark());
    }

    static void apply(SysMenuButton button, MenuButtonSaveRequest request) {
        button.setButtonName(request.getName());
        button.setPermCode(request.getPermission());
        button.setSortNo(request.getSort() == null ? Integer.valueOf(0) : request.getSort());
        button.setStatusFlag(
                request.getStatus() == null ? Integer.valueOf(1) : request.getStatus());
        button.setRemark(request.getRemark());
    }
}
