package com.robot.system.dept.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.robot.common.core.exception.ServiceException;
import com.robot.system.api.entity.SysDepartment;
import com.robot.system.dept.mapper.SysDepartmentMapper;
import java.util.ArrayList;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class DepartmentTreeBehaviorTest {
    private static final int MAX_TREE_DEPTH = 64;

    @Test
    void treeHandlesNullAndBlankRootsLeavesAndEmptyCompanyWhileQueryExcludesInactiveDepartments() {
        var mapper = mock(SysDepartmentMapper.class);
        var service = service(mapper);
        when(mapper.selectList(any()))
                .thenReturn(
                        List.of(
                                dept("a", null),
                                dept("b", " "),
                                dept("child", "a"),
                                dept("orphan", "absent")));
        var tree = service.departmentTree();
        assertEquals(List.of("a", "b"), tree.stream().map(node -> node.id()).toList());
        assertEquals("child", tree.get(0).children().get(0).id());
        assertNull(tree.get(0).children().get(0).children());
        assertNull(tree.get(1).children());
        verify(mapper)
                .selectList(
                        argThat(
                                wrapper ->
                                        wrapper.getSqlSegment().contains("status_flag")
                                                && wrapper.getSqlSegment().contains("delete_flag")
                                                && wrapper.getSqlSegment().contains("ORDER BY")));
        when(mapper.selectList(any())).thenReturn(List.of());
        assertTrue(service.departmentTree().isEmpty());
    }

    @Test
    void dirtyDuplicateParentChainAndExcessiveDepthFailWithoutPartialTree() {
        var mapper = mock(SysDepartmentMapper.class);
        var service = service(mapper);
        when(mapper.selectList(any()))
                .thenReturn(List.of(dept("a", null), dept("b", "a"), dept("a", "b")));
        assertThrows(ServiceException.class, service::departmentTree);
        var deep = new ArrayList<SysDepartment>();
        for (int i = 0; i <= MAX_TREE_DEPTH; i++) {
            deep.add(dept("d" + i, i == 0 ? null : "d" + (i - 1)));
        }
        when(mapper.selectList(any())).thenReturn(deep);
        assertThrows(ServiceException.class, service::departmentTree);
    }

    private static DepartmentService service(SysDepartmentMapper mapper) {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "department-behavior"),
                SysDepartment.class);
        var service = new DepartmentService();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        return service;
    }

    private static SysDepartment dept(String id, String parent) {
        var dept = new SysDepartment();
        dept.setId(id);
        dept.setParentId(parent);
        dept.setDeptName(id);
        dept.setSortNo(1);
        return dept;
    }
}
