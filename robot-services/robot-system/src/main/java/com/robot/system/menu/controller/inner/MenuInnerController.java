package com.robot.system.menu.controller.inner;

import com.robot.api.system.dto.RemoteDataScopeDTO;
import com.robot.api.system.dto.RemoteMenuRouteDTO;
import com.robot.common.core.domain.Result;
import com.robot.system.menu.service.MenuQueryService;
import com.robot.system.permission.service.DataScopeService;
import com.robot.system.security.InnerSystemAccess;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 菜单/权限内部接口（/inner/**：仅供认证服务 Feign 调用；网关已屏蔽外部访问）。 */
@RestController
@RequiredArgsConstructor
public class MenuInnerController {

    private final MenuQueryService menuQueryService;
    private final DataScopeService dataScopeService;
    private final InnerSystemAccess innerAccess;

    @Operation(summary = "内部读取委托用户的菜单路由")
    @GetMapping("/inner/menu/{companyId}/routes")
    public Result<List<RemoteMenuRouteDTO>> routes(
            @RequestParam("userId") String userId, @PathVariable("companyId") String companyId) {
        innerAccess.requireActiveMembership(userId, companyId);
        return Result.success(menuQueryService.routesOfUser(userId, companyId));
    }

    @Operation(summary = "内部读取委托用户的精确按钮权限")
    @GetMapping("/inner/menu/{companyId}/button-list")
    public Result<Map<String, List<String>>> buttonList(
            @RequestParam("userId") String userId, @PathVariable("companyId") String companyId) {
        innerAccess.requireActiveMembership(userId, companyId);
        return Result.success(menuQueryService.buttonListOfUser(userId, companyId));
    }

    @Operation(summary = "内部读取委托用户的数据范围配置")
    @GetMapping("/inner/permission/{companyId}/data-scope")
    public Result<List<RemoteDataScopeDTO>> dataScopes(
            @RequestParam("userId") String userId, @PathVariable("companyId") String companyId) {
        innerAccess.requireActiveMembership(userId, companyId);
        return Result.success(
                dataScopeService.scopesOfUser(userId, companyId).stream()
                        .map(scope -> new RemoteDataScopeDTO(scope.module(), scope.scope()))
                        .toList());
    }
}
