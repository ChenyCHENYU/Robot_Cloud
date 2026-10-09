package com.robot.api.system.feign;

import com.robot.api.system.dto.RemoteLoginLogDTO;
import com.robot.api.system.dto.RemoteMenuRouteDTO;
import com.robot.api.system.dto.RemoteUserContextDTO;
import com.robot.api.system.dto.RemoteUserDTO;
import com.robot.common.core.domain.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/**
 * robot-system 内部接口（/inner/**，仅供服务间调用，网关侧已屏蔽外部访问）。
 *
 * <p>Feign 使用约束（wl-skills-bd standards/22）：
 * <ul>
 *   <li>所有方法必须配置超时（见 robot.openfeign.* 配置），禁止默认无限等待；</li>
 *   <li>调用方必须处理失败降级（fallbackFactory），不允许异常裸穿；</li>
 *   <li>接口语义只读优先；跨服务写操作必须走消息/事件最终一致。</li>
 * </ul>
 */
@FeignClient(name = "robot-system", contextId = "remoteSystemService",
        configuration = com.robot.api.system.config.RemoteSystemFeignConfiguration.class,
        fallbackFactory = RemoteSystemServiceFallbackFactory.class)
public interface RemoteSystemService {

    /** 按用户名查询用户（登录验证）。 */
    @GetMapping("/inner/user/by-username")
    Result<RemoteUserDTO> getUserByUsername(@RequestParam("username") String username);

    /** 按 ID 查询用户（票据 / 刷新令牌链路复验账号状态）。 */
    @GetMapping("/inner/user/{userId}")
    Result<RemoteUserDTO> getUserById(@PathVariable("userId") String userId);

    /** 查询用户全部可用公司上下文（登录前发现）。 */
    @GetMapping("/inner/user/{userId}/contexts")
    Result<List<RemoteUserContextDTO>> getUserContexts(@PathVariable("userId") String userId);

    /** 查询用户在指定公司的菜单树。 */
    @GetMapping("/inner/menu/{companyId}/routes")
    Result<List<RemoteMenuRouteDTO>> getRoutesByUser(@RequestParam("userId") String userId,
                                                     @PathVariable("companyId") String companyId);

    /** 查询用户在指定公司的按钮权限码映射（菜单路径 → 权限码集合）。 */
    @GetMapping("/inner/menu/{companyId}/button-list")
    Result<Map<String, List<String>>> getButtonList(@RequestParam("userId") String userId,
                                                    @PathVariable("companyId") String companyId);

    /** 登录日志打点（尽力而为：失败由服务端吞掉，不影响认证主流程）。 */
    @PostMapping("/inner/log/login-record")
    Result<Void> recordLogin(@RequestBody RemoteLoginLogDTO logEntry);

    /** 查询用户在指定公司的数据权限范围。 */
    @GetMapping("/inner/permission/{companyId}/data-scope")
    Result<List<com.robot.api.system.dto.RemoteDataScopeDTO>> getDataScopes(
            @RequestParam("userId") String userId,
            @PathVariable("companyId") String companyId);
}
