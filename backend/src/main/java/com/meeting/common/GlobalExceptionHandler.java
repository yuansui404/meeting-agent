package com.meeting.common;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private boolean isSseRequest(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        String contentType = request.getContentType();
        return (accept != null && accept.contains("text/event-stream"))
                || "text/event-stream".equals(request.getHeader("Accept"));
    }

    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<?> handleBusiness(BusinessException e, HttpServletRequest request) {
        log.warn("Business exception: {}", e.getMessage());
        if (isSseRequest(request)) {
            return null; // SSE exceptions are handled in the streaming code
        }
        return ApiResponse.error(e.getMessage()).withTraceId(MDC.get("traceId"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    public ApiResponse<?> handleUploadSize() {
        return ApiResponse.error("文件大小超过限制");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<?> handleUnknown(Exception e, HttpServletRequest request) {
        String contentType = request.getContentType();
        if ("text/event-stream".equals(contentType)) {
            log.warn("SSE stream error (handled by streaming code): {}", e.getMessage());
            return null;
        }
        log.error("Unexpected error: ", e);
        return ApiResponse.error("服务器内部错误").withTraceId(MDC.get("traceId"));
    }
}
