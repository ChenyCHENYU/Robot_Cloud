package com.robot.system.dict.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.robot.common.core.domain.Result;
import com.robot.system.api.vo.dict.DictNode;
import com.robot.system.dict.service.DictService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 字典管理接口（前端 /sys/dictionaries 契约：类型+项树形，支持 type/item 双形态提交）。
 */
@Tag(name = "字典管理")
@RestController
@RequestMapping("/sys/dictionaries")
@RequiredArgsConstructor
public class DictController {

    private final DictService dictService;

    @Operation(summary = "字典树")
    @SaCheckPermission("sys:dict:list")
    @GetMapping
    public Result<List<DictNode>> tree() {
        return Result.success(dictService.dictTree());
    }

    @Operation(summary = "按类型编码取字典项")
    @GetMapping("/by-code")
    public Result<List<Map<String, String>>> itemsByCode(@RequestParam("code") String code) {
        return Result.success(dictService.itemsByCode(code));
    }

    @Operation(summary = "新增字典（类型或项）")
    @SaCheckPermission("sys:dict:save")
    @PostMapping
    public Result<String> create(@RequestBody Map<String, Object> body) {
        return Result.success(dictService.create(body));
    }

    @Operation(summary = "更新字典（类型或项）")
    @SaCheckPermission("sys:dict:save")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        dictService.update(id, body);
        return Result.success();
    }

    @Operation(summary = "启用/停用字典项")
    @SaCheckPermission("sys:dict:save")
    @PutMapping("/{id}/status")
    public Result<Void> toggleStatus(@PathVariable String id,
                                     @RequestBody Map<String, Integer> body) {
        dictService.toggleStatus(id, body.get("status"));
        return Result.success();
    }


    @Operation(summary = "删除字典（类型或项，软删）")
    @SaCheckPermission("sys:dict:delete")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable String id) {
        dictService.delete(id);
        return Result.success();
    }
}
