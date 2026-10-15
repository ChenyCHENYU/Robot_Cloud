package com.robot.system.dept.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.robot.common.core.constant.RobotConstants;
import com.robot.common.core.domain.ResultCode;
import com.robot.common.core.exception.ServiceAssert;
import com.robot.system.api.entity.SysDepartment;
import com.robot.system.api.vo.dept.DepartmentNode;
import com.robot.system.dept.mapper.SysDepartmentMapper;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** 部门服务：租户拦截器自动过滤 COMPANY_ID。 */
@Service
public class DepartmentService extends ServiceImpl<SysDepartmentMapper, SysDepartment> {
    private static final int MAX_TREE_DEPTH = 64;

    /**
     * 仅返回租户插件过滤后的当前公司活跃部门树。
     *
     * @return 当前事实派生的结果
     */
    public List<DepartmentNode> departmentTree() {
        Map<String, List<SysDepartment>> byParent =
                activeDepartments().stream()
                        .collect(
                                Collectors.groupingBy(
                                        department ->
                                                department.getParentId() == null
                                                                || department
                                                                        .getParentId()
                                                                        .isBlank()
                                                        ? ""
                                                        : department.getParentId()));
        return buildTree(byParent, "", 0, new HashSet<>());
    }

    private List<SysDepartment> activeDepartments() {
        return list(
                new LambdaQueryWrapper<SysDepartment>()
                        .eq(SysDepartment::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE)
                        .eq(SysDepartment::getStatusFlag, 1)
                        .orderByAsc(SysDepartment::getSortNo, SysDepartment::getId));
    }

    private List<DepartmentNode> buildTree(
            Map<String, List<SysDepartment>> departments,
            String parentId,
            int depth,
            Set<String> visiting) {
        ServiceAssert.isTrue(depth <= MAX_TREE_DEPTH, ResultCode.PARAM_INVALID, "部门树超过允许深度");
        return departments.getOrDefault(parentId, List.of()).stream()
                .map(
                        dept -> {
                            ServiceAssert.isTrue(
                                    visiting.add(dept.getId()), ResultCode.PARAM_INVALID, "部门父链异常");
                            List<DepartmentNode> children =
                                    buildTree(departments, dept.getId(), depth + 1, visiting);
                            visiting.remove(dept.getId());
                            return new DepartmentNode(
                                    dept.getId(),
                                    dept.getParentId(),
                                    dept.getDeptName(),
                                    dept.getDeptName(),
                                    dept.getSortNo(),
                                    children.isEmpty() ? null : children);
                        })
                .toList();
    }
}
