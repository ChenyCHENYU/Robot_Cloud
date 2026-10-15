package com.robot.system.menu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceException;
import com.robot.system.api.dto.menu.MenuButtonSaveRequest;
import com.robot.system.api.dto.menu.MenuSaveRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** 写入前拒绝危险路由、组件及权限；目录与无菜单权限的合法输入仍可保存。 */
class MenuWriteValuesBehaviorTest {
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
            strings = {
                " ",
                "/page name",
                "/page?edit=1",
                "/page#edit",
                "/./page",
                "/page/../other",
                "javascript:alert(1)",
                "JaVaScRiPt:alert(1)",
                "data:text/html,hello"
            })
    void rejectsUnsafeRoutePaths(String path) {
        MenuSaveRequest request = page();
        request.setPath(path);
        assertInvalid(() -> MenuWriteValues.validateMenu(request), "菜单路由路径不合法");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
            strings = {
                " ",
                "../outside",
                "/page/../outside",
                "/page?mode=edit",
                "javascript:alert(1)",
                "page with spaces"
            })
    void rejectsMissingOrUnsafePageComponents(String component) {
        MenuSaveRequest request = page();
        request.setComponent(component);
        assertInvalid(() -> MenuWriteValues.validateMenu(request), "页面组件路径不合法");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "sys:page:list", "robot-system.page:read"})
    void allowsOptionalMenuPermissionAndExactResourceActions(String permission) {
        MenuSaveRequest request = page();
        request.setPermission(permission);
        assertThatCode(() -> MenuWriteValues.validateMenu(request)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"*", "sys:*", "sys:page:*", "sys", "sys:page:list ", ":list"})
    void rejectsNonExactMenuPermissions(String permission) {
        MenuSaveRequest request = page();
        request.setPermission(permission);
        assertInvalid(() -> MenuWriteValues.validateMenu(request), "权限标识必须是精确资源动作");
    }

    @Test
    void allowsDirectoryWithoutAComponent() {
        MenuSaveRequest request = page();
        request.setType("directory");
        request.setComponent(null);
        assertThatCode(() -> MenuWriteValues.validateMenu(request)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"button", "MENU", " "})
    void rejectsUnsupportedMenuKinds(String type) {
        MenuSaveRequest request = page();
        request.setType(type);
        assertInvalid(
                () -> MenuWriteValues.validateMenu(request), ResultCode.PARAM_INVALID.getMessage());
    }

    @Test
    void rejectsConflictingVisibilityAndCacheFlags() {
        MenuSaveRequest request = page();
        request.setHidden(1);
        request.setVisibleFlag(1);
        assertInvalid(
                () -> MenuWriteValues.validateMenu(request), ResultCode.PARAM_INVALID.getMessage());
        request.setVisibleFlag(0);
        request.setKeepAlive(true);
        request.setKeepAliveFlag(0);
        assertInvalid(
                () -> MenuWriteValues.validateMenu(request), ResultCode.PARAM_INVALID.getMessage());
        request.setKeepAliveFlag(1);
        assertThatCode(() -> MenuWriteValues.validateMenu(request)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "*", "sys:*", "sys", "sys:page:save "})
    void buttonsRequireAnExactResourceAction(String permission) {
        MenuButtonSaveRequest request = new MenuButtonSaveRequest();
        request.setName("Save");
        request.setPermission(permission);
        assertInvalid(() -> MenuWriteValues.validateButtonFields(request), "权限标识必须是精确资源动作");
    }

    @Test
    void allowsExactButtonPermissionsButRejectsBlankNames() {
        MenuButtonSaveRequest request = new MenuButtonSaveRequest();
        request.setName("Save");
        request.setPermission("sys:page:save");
        assertThatCode(() -> MenuWriteValues.validateButtonFields(request))
                .doesNotThrowAnyException();
        request.setName(" ");
        assertInvalid(
                () -> MenuWriteValues.validateButtonFields(request),
                ResultCode.PARAM_INVALID.getMessage());
        MenuSaveRequest menu = page();
        menu.setName(null);
        assertInvalid(
                () -> MenuWriteValues.validateMenu(menu), ResultCode.PARAM_INVALID.getMessage());
    }

    private static MenuSaveRequest page() {
        MenuSaveRequest request = new MenuSaveRequest();
        request.setName("Page");
        request.setType("menu");
        request.setPath("/page");
        request.setComponent("/page/index");
        return request;
    }

    private static void assertInvalid(Runnable operation, String message) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(
                        ServiceException.class,
                        error -> {
                            assertThat(error.getCode())
                                    .isEqualTo(ResultCode.PARAM_INVALID.getCode());
                            assertThat(error.getMessage()).isEqualTo(message);
                        });
    }
}
