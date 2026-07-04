package com.meeting.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.io.IOException;
import java.io.PrintWriter;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private boolean isSseRequest(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        return accept != null && accept.contains("text/event-stream");
    }

    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<?> handleBusiness(BusinessException e, HttpServletRequest request, HttpServletResponse response) {
        log.warn("Business exception: {}", e.getMessage());
        if (isSseRequest(request)) {
            writeSseError(response, e.getMessage());
            return null;
        }
        return ApiResponse.error(e.getMessage()).withTraceId(MDC.get("traceId"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    public ApiResponse<?> handleUploadSize() {
        return ApiResponse.error("文件大小超过限制").withTraceId(MDC.get("traceId"));
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<?> handleUnknown(Exception e, HttpServletRequest request, HttpServletResponse response) {
        if (isSseRequest(request)) {
            log.error("SSE stream error: ", e);
            writeSseError(response, "服务器内部错误");
            return null;
        }
        log.error("Unexpected error: ", e);
        return ApiResponse.error("服务器内部错误").withTraceId(MDC.get("traceId"));
    }

    private void writeSseError(HttpServletResponse response, String message) {
        try {
            response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
            response.setCharacterEncoding("UTF-8");
            PrintWriter writer = response.getWriter();
            writer.write("event: error\n");
            writer.write("data: " + message + "\n\n");
            writer.flush();
        } catch (IOException ignored) {
            // client already disconnected
        }
    }
}
