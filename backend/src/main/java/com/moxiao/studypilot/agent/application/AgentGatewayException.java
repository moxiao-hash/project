package com.moxiao.studypilot.agent.application;

import org.springframework.http.HttpStatusCode;

/**
 * Python Agent 服务调用失败。
 *
 * <p>异常只保存已经筛选过的公开错误说明，不保留上游完整响应、令牌或请求正文，
 * 避免全局异常日志意外记录用户资料和服务密钥。</p>
 */
public class AgentGatewayException extends RuntimeException {

    private final HttpStatusCode status;
    private final String retryAfter;

    public AgentGatewayException(HttpStatusCode status, String message) {
        this(status, message, null);
    }

    public AgentGatewayException(
            HttpStatusCode status,
            String message,
            String retryAfter
    ) {
        super(message);
        this.status = status;
        this.retryAfter = retryAfter;
    }

    public HttpStatusCode status() {
        return status;
    }

    public String retryAfter() {
        return retryAfter;
    }
}
