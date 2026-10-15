package com.robot.system.menu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceException;
import com.robot.system.api.dto.menu.MenuButtonSaveRequest;
import com.robot.system.api.dto.menu.MenuSaveRequest;
import com.robot.system.api.entity.SysMenu;
import com.robot.system.api.entity.SysMenuButton;
import com.robot.system.menu.mapper.SysMenuButtonMapper;
import com.robot.system.menu.mapper.SysMenuMapper;
import com.robot.system.permission.service.PermissionChangeRecorder;
import com.robot.system.permission.service.PermissionStateStore;
import com.robot.system.role.mapper.SysRoleButtonMapper;
import com.robot.system.role.mapper.SysRoleMenuMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** 数据库条件更新返回 0 必须报告冲突；失败不能审计成成功。 */
@ExtendWith(MockitoExtension.class)
class MenuMutationFailureTest {
    private static final int ORIGINAL_REVISION = 3;

    @Mock SysMenuMapper menuMapper;
    @Mock SysMenuButtonMapper buttonMapper;
    @Mock SysRoleMenuMapper roleMenuMapper;
    @Mock SysRoleButtonMapper roleButtonMapper;
    @Mock PermissionChangeRecorder recorder;
    @Mock PermissionStateStore state;
    private MenuService service;
    private SysMenu page;

    @BeforeEach
    void setup() {
        service =
                new MenuService(
                        buttonMapper,
                        roleMenuMapper,
                        roleButtonMapper,
                        recorder,
                        state,
                        mock(com.robot.system.permission.service.GlobalDirectoryPolicy.class));
        ReflectionTestUtils.setField(service, "baseMapper", menuMapper);
        page = new SysMenu();
        page.setId("page");
        page.setMenuType("menu");
        page.setMenuName("Page");
        page.setPath("/page");
        page.setComponent("/page/index");
        page.setStatusFlag(1);
        page.setDeleteFlag(1);
        page.setRevision(ORIGINAL_REVISION);
        when(menuMapper.selectList(any())).thenReturn(List.of(page));
    }

    @Test
    void zeroRowMenuUpdateLocksBeforeReadAndDoesNotRecordSuccess() {
        MenuSaveRequest request = new MenuSaveRequest();
        request.setName("Changed");
        request.setType("menu");
        request.setPath("/page");
        request.setComponent("/page/index");
        request.setRevision(ORIGINAL_REVISION);
        when(menuMapper.updateById(any(SysMenu.class))).thenReturn(0);
        assertConflict(() -> service.updateMenu("page", request));
        var order = inOrder(state, menuMapper);
        order.verify(state).lockMenuTree();
        order.verify(menuMapper).selectList(any());
        order.verify(menuMapper).updateById(any(SysMenu.class));
        verifyNoInteractions(recorder);
    }

    @Test
    void zeroRowButtonUpdateDoesNotRecordSuccess() {
        SysMenuButton button = new SysMenuButton();
        button.setId("button");
        button.setMenuId("page");
        button.setDeleteFlag(1);
        button.setRevision(2);
        when(buttonMapper.selectById("button")).thenReturn(button);
        when(buttonMapper.selectCount(any())).thenReturn(0L);
        when(buttonMapper.updateById(any(SysMenuButton.class))).thenReturn(0);
        MenuButtonSaveRequest request = new MenuButtonSaveRequest();
        request.setName("Save");
        request.setPermission("sys:page:save");
        request.setRevision(2);
        assertConflict(() -> service.updateButton("button", request));
        var order = inOrder(state, buttonMapper);
        order.verify(state).lockMenuTree();
        order.verify(buttonMapper).selectById("button");
        verifyNoInteractions(recorder);
    }

    private static void assertConflict(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(
                        ServiceException.class,
                        error ->
                                assertThat(error.getCode())
                                        .isEqualTo(ResultCode.DATA_CONCURRENT_CONFLICT.getCode()));
    }
}
