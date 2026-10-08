package com.robot.auth.authn.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 切换企业上下文请求（前端仅发送 contextId，需有效登录态，
 * 与 activate 的 loginTicket 形态不同，禁止复用同一 DTO）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SwitchContextRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "上下文 ID 不能为空")
    private String contextId;
}
