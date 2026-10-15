package com.robot.system.permission.config;

import com.robot.system.permission.service.CacheEventDispatcher;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** 重试扫描只消费已经提交的控制事件，多实例重复投递保持幂等。 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(
        name = "robot.cache-outbox.enabled",
        havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
public class CacheEventRetryConfiguration {

    private final CacheEventDispatcher dispatcher;

    /** 创建或返回该类型的明确协作结果。 */
    @Scheduled(fixedDelayString = "${robot.cache-outbox.retry-delay-ms:5000}")
    public void retryPending() {
        dispatcher.retryPending();
    }
}
