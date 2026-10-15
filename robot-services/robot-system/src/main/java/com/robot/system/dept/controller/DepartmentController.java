package com.robot.system.dept.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.robot.common.core.domain.Result;
import com.robot.system.api.vo.dept.DepartmentNode;
import com.robot.system.dept.service.DepartmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 部门接口（前端 /sys/departments 契约：返回公司内部门树）。 */
@Tag(name = "部门管理")
@RestController
@RequestMapping("/sys/departments")
@RequiredArgsConstructor
public class DepartmentController {

    private final DepartmentService departmentService;

    @Operation(summary = "部门树（当前公司）")
    @SaCheckPermission("sys:user:list")
    @GetMapping
    public Result<List<DepartmentNode>> tree() {
        return Result.success(departmentService.departmentTree());
    }
}
