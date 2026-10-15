package com.robot.api.system.feign;

import com.robot.api.system.dto.RemoteLoginLogDTO;
import com.robot.api.system.dto.RemoteMenuRouteDTO;
import com.robot.api.system.dto.RemoteUserContextDTO;
import com.robot.api.system.dto.RemoteUserDTO;
import com.robot.common.core.domain.Result;
import java.util.List;
import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * robot-system 内部接口（/inner/**，仅供服务间调用，网关侧已屏蔽外部访问）。
 *
 * <p>Feign 使用约束（wl-skills-bd standards/22）：
 *
 * <ul>
 *   <li>所有方法必须配置超时（见 robot.openfeign.* 配置），禁止默认无限等待；
 *   <li>调用方必须处理失败降级（fallbackFactory），不允许异常裸穿；
 *   <li>接口语义只读优先；跨服务写操作必须走消息/事件最终一致。
 * </ul>
 */
@FeignClient(
        name = "robot-system",
        contextId = "remoteSystemService",
        configuration = com.robot.api.system.config.RemoteSystemFeignConfiguration.class,
        fallbackFactory = RemoteSystemServiceFallbackFactory.class)
public interface RemoteSystemService {

    /**
     * 按用户名查询用户（登录验证）。
     *
     * @param username 待验证的登录用户名
     * @return 全局账号事实，错误时明确失败
     */
    @GetMapping("/inner/user/by-username")
    Result<RemoteUserDTO> getUserByUsername(@RequestParam("username") String username);

    /**
     * 按 ID 查询用户（票据 / 刷新令牌链路复验账号状态）。
     *
     * @param userId 已认证账号标识
     * @return 最新全局账号事实，错误时明确失败
     */
    @GetMapping("/inner/user/{userId}")
    Result<RemoteUserDTO> getUserById(@PathVariable("userId") String userId);

    /**
     * 查询用户全部可用公司上下文（登录前发现）。
     *
     * @param userId 已复验的账号标识
     * @return 有效公司成员上下文，供激活时继续复验
     */
    @GetMapping("/inner/user/{userId}/contexts")
    Result<List<RemoteUserContextDTO>> getUserContexts(@PathVariable("userId") String userId);

    /**
     * 查询用户在指定公司的菜单树。
     *
     * @param userId 当前认证账号标识
     * @param companyId 当前有效公司标识
     * @return 仅包含该公司有效授权的路由树
     */
    @GetMapping("/inner/menu/{companyId}/routes")
    Result<List<RemoteMenuRouteDTO>> getRoutesByUser(
            @RequestParam("userId") String userId, @PathVariable("companyId") String companyId);

    /**
     * 查询用户在指定公司的按钮权限码映射（菜单路径 → 权限码集合）。
     *
     * @param userId 当前认证账号标识
     * @param companyId 当前有效公司标识
     * @return 路由路径到有效按钮权限码的映射
     */
    @GetMapping("/inner/menu/{companyId}/button-list")
    Result<Map<String, List<String>>> getButtonList(
            @RequestParam("userId") String userId, @PathVariable("companyId") String companyId);

    /**
     * 幂等接收登录审计；持久化成功才ACK，异常传播，由Auth可靠队列重试。
     *
     * @param logEntry 带稳定幂等事件标识的登录审计
     * @return 仅在持久化已确认时成功，故障必须失败以便重试
     */
    @PostMapping("/inner/log/login-record")
    Result<Void> recordLogin(@RequestBody RemoteLoginLogDTO logEntry);

    /**
     * 查询用户在指定公司的数据权限范围。
     *
     * @param userId 当前认证账号标识
     * @param companyId 当前有效公司标识
     * @return 该公司有效角色合并后的范围摘要
     */
    @GetMapping("/inner/permission/{companyId}/data-scope")
    Result<List<com.robot.api.system.dto.RemoteDataScopeDTO>> getDataScopes(
            @RequestParam("userId") String userId, @PathVariable("companyId") String companyId);
}
