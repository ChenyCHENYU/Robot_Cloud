package com.robot.api.system.feign;

import com.robot.api.system.dto.RemoteLoginLogDTO;
import com.robot.api.system.dto.RemoteMenuRouteDTO;
import com.robot.api.system.dto.RemoteUserContextDTO;
import com.robot.api.system.dto.RemoteUserDTO;
import com.robot.common.core.domain.Result;
import com.robot.common.core.domain.ResultCode;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/** robot-system 降级工厂：所有方法返回统一失败码并记录被调方不可用， 由调用方按业务决定终止（登录类）或提示重试（查询类）。 */
@Slf4j
@Component
public class RemoteSystemServiceFallbackFactory implements FallbackFactory<RemoteSystemService> {

    @Override
    public RemoteSystemService create(Throwable cause) {
        log.error("robot-system 服务调用失败，触发明确失败降级");
        return new UnavailableSystemService();
    }

    /** 无外部实例引用的失败实现；可靠审计不能将故障响应作为持久化确认。 */
    private static final class UnavailableSystemService implements RemoteSystemService {

        @Override
        public Result<RemoteUserDTO> getUserByUsername(String username) {
            return Result.failed(ResultCode.FAILED, "系统服务暂不可用，请稍后重试");
        }

        @Override
        public Result<RemoteUserDTO> getUserById(String userId) {
            return Result.failed(ResultCode.FAILED, "系统服务暂不可用，请稍后重试");
        }

        @Override
        public Result<List<RemoteUserContextDTO>> getUserContexts(String userId) {
            return Result.failed(ResultCode.FAILED, "系统服务暂不可用，请稍后重试");
        }

        @Override
        public Result<List<RemoteMenuRouteDTO>> getRoutesByUser(String userId, String companyId) {
            return Result.failed(ResultCode.FAILED, "系统服务暂不可用，请稍后重试");
        }

        @Override
        public Result<Map<String, List<String>>> getButtonList(String userId, String companyId) {
            return Result.failed(ResultCode.FAILED, "系统服务暂不可用，请稍后重试");
        }

        @Override
        public Result<Void> recordLogin(RemoteLoginLogDTO logEntry) {
            return Result.failed(ResultCode.FAILED, "系统服务暂不可用，请稍后重试");
        }

        @Override
        public Result<List<com.robot.api.system.dto.RemoteDataScopeDTO>> getDataScopes(
                String userId, String companyId) {
            return Result.failed(ResultCode.FAILED, "系统服务暂不可用，请稍后重试");
        }
    }
}
