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

    /** 兜底：记录堆栈但不把内部信息返回给用户。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Dtos.ErrorResponse> handleUnexpected(Exception e, HttpServletRequest request) {
        log.error("未预期异常：{}", request.getRequestURI(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new Dtos.ErrorResponse("INTERNAL_ERROR",
                        "服务内部错误，请稍后重试。如果持续出现，请查看服务端日志。",
                        request.getRequestURI(), Instant.now(), List.of()));
    }
}
