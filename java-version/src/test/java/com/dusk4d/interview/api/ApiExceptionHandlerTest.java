package com.dusk4d.interview.api;

import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 兜底异常处理器的边界：不能对「浏览器关页面」和「静态资源」也吐 JSON 错误体。
 *
 * <p>回归背景（真实日志）：一次浏览器刷新会让服务端打出两段堆栈——
 * <pre>
 * ERROR 未预期异常：/index.html
 *   org.apache.catalina.connector.ClientAbortException: ... 中止了一个已建立的连接。
 * WARN  Failure in @ExceptionHandler ...handleUnexpected
 *   HttpMessageNotWritableException: No converter for [class Dtos$ErrorResponse]
 *   with preset Content-Type 'text/html'
 * </pre>
 * 前者不是服务端错误，后者是因为静态资源响应的 Content-Type 已是 text/html，
 * 根本无法写入 JSON 正文。真正的问题会被这两段噪音淹没。
 */
@DisplayName("兜底异常处理：客户端断连与静态资源")
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler =
            new ApiExceptionHandler(Clock.fixed(Instant.parse("2024-06-01T10:00:00Z"), ZoneOffset.UTC));

    private MockHttpServletResponse response() {
        return new MockHttpServletResponse();
    }

    @Test
    @DisplayName("客户端断开连接（ClientAbortException）→ 不返回错误体，不按服务端错误处理")
    void clientAbortIsSwallowed() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/index.html");
        ClientAbortException abort = new ClientAbortException(
                new IOException("你的主机中的软件中止了一个已建立的连接。"));

        ResponseEntity<Dtos.ErrorResponse> result = handler.handleUnexpected(abort, request, response());

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("被包装在 cause 链里的断连异常同样能识别")
    void nestedClientAbortIsDetected() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/index.html");
        Exception wrapped = new IllegalStateException("wrapper",
                new RuntimeException("outer", new ClientAbortException(new IOException("Broken pipe"))));

        assertThat(handler.handleUnexpected(wrapped, request, response())).isNull();
    }

    @Test
    @DisplayName("普通 IOException 里出现 Broken pipe 也按断连处理")
    void brokenPipeMessageIsTreatedAsDisconnect() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/app.js");
        IOException brokenPipe = new IOException("Broken pipe");

        assertThat(handler.handleUnexpected(brokenPipe, request, response())).isNull();
    }

    @Test
    @DisplayName("非 /api 请求的真异常 → 状态码 500、不写 JSON 正文（避免 text/html 转换失败）")
    void nonApiRequestGetsStatusWithoutJsonBody() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/index.html");
        MockHttpServletResponse response = response();

        ResponseEntity<Dtos.ErrorResponse> result =
                handler.handleUnexpected(new IllegalStateException("boom"), request, response);

        assertThat(result).isNull();
        assertThat(response.getStatus()).isEqualTo(500);
    }

    @Test
    @DisplayName("/api 请求的真异常 → 仍然返回结构化 500 JSON（原有契约不变）")
    void apiRequestStillReturnsJsonError() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/interviews");

        ResponseEntity<Dtos.ErrorResponse> result =
                handler.handleUnexpected(new IllegalStateException("boom"), request, response());

        assertThat(result).isNotNull();
        assertThat(result.getStatusCode().value()).isEqualTo(500);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().code()).isEqualTo("INTERNAL_ERROR");
        assertThat(result.getBody().path()).isEqualTo("/api/interviews");
    }

    @Test
    @DisplayName("响应已提交时不再改状态码（避免二次异常）")
    void committedResponseIsLeftAlone() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/styles.css");
        MockHttpServletResponse response = response();
        response.setCommitted(true);
        response.setStatus(200);

        assertThat(handler.handleUnexpected(new IllegalStateException("boom"), request, response)).isNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
