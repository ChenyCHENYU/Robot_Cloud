package com.robot.auth.config;

import feign.RetryableException;
import feign.Retryer;

/** Feign 重试策略：默认不自动重试（登录链路重试会放大凭据尝试次数， 失败快速返回由用户侧重试；幂等查询可在 client 级配置覆盖）。 */
public final class FeignNoRetryer implements Retryer {

    @Override
    public void continueOrPropagate(RetryableException e) {
        throw e;
    }

    @Override
    public Retryer clone() {
        return this;
    }
}
