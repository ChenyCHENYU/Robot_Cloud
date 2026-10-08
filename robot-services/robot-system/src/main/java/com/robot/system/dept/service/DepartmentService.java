package com.robot.system.dept.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.robot.common.core.constant.RobotConstants;
import com.robot.system.api.entity.SysDepartment;
import com.robot.system.api.vo.dept.DepartmentNode;
import com.robot.system.dept.mapper.SysDepartmentMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/** 部门服务：租户拦截器自动过滤 COMPANY_ID。 */
@Service
public class DepartmentService extends ServiceImpl<SysDepartmentMapper, SysDepartment> {

    public List<DepartmentNode> departmentTree() {
        return buildTree(activeDepartments(), null);
    }

    private List<SysDepartment> activeDepartments() {
        return list(new LambdaQueryWrapper<SysDepartment>()
                .eq(SysDepartment::getDeleteFlag, RobotConstants.DELETE_FLAG_ACTIVE)
                .eq(SysDepartment::getStatusFlag, 1)
                .orderByAsc(SysDepartment::getSortNo));
    }

    private List<DepartmentNode> buildTree(List<SysDepartment> departments, String parentId) {
        return departments.stream()
                .filter(dept -> parentId == null
                        ? dept.getParentId() == null || dept.getParentId().isBlank()
                        : parentId.equals(dept.getParentId()))
                .map(dept -> {
                    List<DepartmentNode> children = buildTree(departments, dept.getId());
                    return new DepartmentNode(dept.getId(), dept.getParentId(),
                            dept.getDeptName(), dept.getDeptName(), dept.getSortNo(),
                            children.isEmpty() ? null : children);
                })
                .toList();
    }
}
