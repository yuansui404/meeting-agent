package com.meeting.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private boolean isSseRequest(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        return accept != null && accept.contains("text/event-stream");
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<?>> handleBusiness(BusinessException e, HttpServletRequest request, HttpServletResponse response) {
        HttpStatus status = resolveStatus(e.getCode());
        String codeStr = String.valueOf(e.getCode());

        if (status.is5xxServerError()) {
            log.error("Business error [{}]: {}", codeStr, e.getMessage(), e);
        } else {
            log.warn("Business error [{}]: {}", codeStr, e.getMessage());
        }

        if (isSseRequest(request)) {
            writeSseError(response, e.getMessage());
            return null;
        }

        ApiResponse<?> body = ApiResponse.error(e.getMessage(), codeStr)
                .withTraceId(MDC.get("traceId"));
        return ResponseEntity.status(status).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<?>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.warn("Validation failed: {}", message);
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(message, "100001").withTraceId(MDC.get("traceId")));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<?>> handleUploadSize() {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiResponse.error("文件大小超过限制").withTraceId(MDC.get("traceId")));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<?>> handleUnknown(Exception e, HttpServletRequest request, HttpServletResponse response) {
        log.error("Unexpected error: ", e);

        if (isSseRequest(request)) {
            writeSseError(response, "服务器内部错误");
            return null;
        }

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("服务器内部错误").withTraceId(MDC.get("traceId")));
    }

    private HttpStatus resolveStatus(int code) {
        if (code >= 100000 && code < 200000) {
            return code == 100002 ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
        } else if (code >= 200000 && code < 300000) {
            return HttpStatus.BAD_GATEWAY;
        } else if (code >= 300000 && code < 400000) {
            return HttpStatus.UNPROCESSABLE_ENTITY;
        }
        return HttpStatus.BAD_REQUEST;
    }

    private void writeSseError(HttpServletResponse response, String message) {
        try {
            response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
            response.setCharacterEncoding("UTF-8");
            PrintWriter writer = response.getWriter();
            writer.write("event: error\n");
            writer.write("data: " + MAPPER.writeValueAsString(message) + "\n\n");
            writer.flush();
        } catch (IOException ignored) {
            // client already disconnected
        }
    }
}
