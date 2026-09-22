package com.dusk4d.interview.api;

import com.dusk4d.interview.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 统一异常处理。
 *
 * <p>目标（对应方案书第八节）：模型超时、非法 JSON、检索为空、文件过大等都不能暴露成 500 堆栈。
 * 所有错误都返回稳定的 {@link Dtos.ErrorResponse}，并记录不含简历原文的日志。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final Clock clock;

    public ApiExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Dtos.ErrorResponse> handleApi(ApiException e, HttpServletRequest request) {
        log.warn("业务异常 [{}] {} -> {}", e.code(), request.getRequestURI(), e.getMessage());
        return ResponseEntity.status(e.status())
                .body(new Dtos.ErrorResponse(e.code(), e.getMessage(), request.getRequestURI(),
                        clock.instant(), List.of()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Dtos.ErrorResponse> handleUnreadable(HttpMessageNotReadableException e,
                                                               HttpServletRequest request) {
        log.warn("请求体解析失败：{} -> {}", request.getRequestURI(), e.getMessage());
        return ResponseEntity.badRequest()
                .body(new Dtos.ErrorResponse("INVALID_REQUEST_BODY",
                        "请求体不是合法 JSON，请检查字段名与格式。", request.getRequestURI(),
                        clock.instant(), List.of()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Dtos.ErrorResponse> handleTooLarge(MaxUploadSizeExceededException e,
                                                             HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new Dtos.ErrorResponse("FILE_TOO_LARGE",
                        "上传文件超过大小限制（默认 10MB），请压缩后重试。", request.getRequestURI(),
                        clock.instant(), List.of()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Dtos.ErrorResponse> handleMethod(HttpRequestMethodNotSupportedException e,
                                                           HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(new Dtos.ErrorResponse("METHOD_NOT_ALLOWED",
                        "该路径不支持 " + e.getMethod() + " 方法。", request.getRequestURI(),
                        clock.instant(), List.of()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Dtos.ErrorResponse> handleNoResource(NoResourceFoundException e,
                                                               HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new Dtos.ErrorResponse("NOT_FOUND",
                        "接口或静态资源不存在：" + request.getRequestURI(), request.getRequestURI(),
                        clock.instant(), List.of()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Dtos.ErrorResponse> handleIllegalArgument(IllegalArgumentException e,
                                                                    HttpServletRequest request) {
        log.warn("参数错误：{} -> {}", request.getRequestURI(), e.getMessage());
        return ResponseEntity.badRequest()
                .body(new Dtos.ErrorResponse("VALIDATION_ERROR", e.getMessage(), request.getRequestURI(),
                        clock.instant(), List.of()));
    }

    /**
     * 兜底：记录堆栈但不把内部信息返回给用户。
     *
     * <p>这里踩过两个坑，都是实测日志里发现的（一次浏览器刷新就在日志里刷两段堆栈）：
     * <ol>
     *   <li><b>客户端主动断开</b>（关标签页、刷新、跳转）会以 {@code ClientAbortException} 抛到这里。
     *       它不是服务端错误，按 ERROR 记录只会把真正的故障淹没在噪音里；</li>
     *   <li><b>非 /api 请求</b>（静态资源、SPA 页面）的响应 Content-Type 已经确定为 {@code text/html}，
     *       此时再返回 {@code ErrorResponse} 会二次抛 {@code HttpMessageNotWritableException}
     *       （"No converter for [Dtos$ErrorResponse] with preset Content-Type 'text/html'"），
     *       于是同一个请求打出两段堆栈。</li>
     * </ol>
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Dtos.ErrorResponse> handleUnexpected(Exception e, HttpServletRequest request,
                                                               HttpServletResponse response) {
        if (isClientDisconnect(e)) {
            log.debug("客户端提前断开连接：{}（浏览器关闭或跳转，非服务端错误）", request.getRequestURI());
            return null;
        }
        if (!isApiRequest(request)) {
            // 浏览器要的是 HTML/静态资源，JSON 错误体既写不出去也没有意义；
            // 这里只保证状态码是 500 并留日志，正文交给容器处理。
            log.error("静态资源请求处理失败：{}", request.getRequestURI(), e);
            if (!response.isCommitted()) {
                response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
            }
            return null;
        }
        log.error("未预期异常：{}", request.getRequestURI(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new Dtos.ErrorResponse("INTERNAL_ERROR",
                        "服务内部错误，请稍后重试。如果持续出现，请查看服务端日志。",
                        request.getRequestURI(), Instant.now(), List.of()));
    }

    /**
     * 是否为「客户端断开连接」导致的异常。
     *
     * <p>按类名判断而不是直接 import Tomcat 的 {@code ClientAbortException}：
     * 异常处理层不应该绑死在某个 Servlet 容器实现上，换容器后同名的断开异常仍能被识别。
     * 顺带识别 "Broken pipe" / "Connection reset"，它们在 JDK 底层会以普通 IOException 冒出来。
     */
    private boolean isClientDisconnect(Throwable e) {
        Throwable current = e;
        // 上限保护：cause 链理论上可能自引用，而在异常处理器里死循环是最糟的情况。
        for (int depth = 0; current != null && depth < 10; depth++) {
            if ("org.apache.catalina.connector.ClientAbortException".equals(current.getClass().getName())) {
                return true;
            }
            String message = current.getMessage();
            if (message != null
                    && (message.contains("Broken pipe") || message.contains("Connection reset")
                        || message.contains("中止了一个已建立的连接"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /** 只有 /api/** 才返回结构化 JSON 错误体（其余是页面与静态资源）。 */
    private boolean isApiRequest(HttpServletRequest request) {
        String uri = request == null ? null : request.getRequestURI();
        return uri != null && uri.startsWith("/api/");
    }
}
