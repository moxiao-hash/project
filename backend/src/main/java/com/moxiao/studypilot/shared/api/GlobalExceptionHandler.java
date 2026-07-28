package com.moxiao.studypilot.shared.api;

import com.moxiao.studypilot.agent.application.AgentGatewayException;
import com.moxiao.studypilot.shared.error.ConflictException;
import com.moxiao.studypilot.shared.error.InvalidCredentialsException;
import com.moxiao.studypilot.shared.error.ResourceNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request
    ) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
                fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage())
        );
        return build(HttpStatus.BAD_REQUEST, "请求参数校验失败", request, fieldErrors);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(
            IllegalArgumentException exception,
            HttpServletRequest request
    ) {
        return build(HttpStatus.BAD_REQUEST, exception.getMessage(), request, null);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiError> handleConflict(
            ConflictException exception,
            HttpServletRequest request
    ) {
        return build(HttpStatus.CONFLICT, exception.getMessage(), request, null);
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ApiError> handleInvalidCredentials(
            InvalidCredentialsException exception,
            HttpServletRequest request
    ) {
        return build(HttpStatus.UNAUTHORIZED, exception.getMessage(), request, null);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(
            ResourceNotFoundException exception,
            HttpServletRequest request
    ) {
        return build(HttpStatus.NOT_FOUND, exception.getMessage(), request, null);
    }

    @ExceptionHandler(AgentGatewayException.class)
    public ResponseEntity<ApiError> handleAgentGateway(
            AgentGatewayException exception,
            HttpServletRequest request
    ) {
        ResponseEntity<ApiError> response = build(
                exception.status(),
                exception.getMessage(),
                request,
                null
        );
        if (exception.retryAfter() == null) {
            return response;
        }
        return ResponseEntity.status(exception.status())
                .header("Retry-After", exception.retryAfter())
                .body(response.getBody());
    }

    private ResponseEntity<ApiError> build(
            org.springframework.http.HttpStatusCode status,
            String message,
            HttpServletRequest request,
            Map<String, String> fieldErrors
    ) {
        ApiError error = new ApiError(
                Instant.now(),
                status.value(),
                HttpStatus.valueOf(status.value()).getReasonPhrase(),
                message,
                request.getRequestURI(),
                fieldErrors
        );
        return ResponseEntity.status(status).body(error);
    }
}
