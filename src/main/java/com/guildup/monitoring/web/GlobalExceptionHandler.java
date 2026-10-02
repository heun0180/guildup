package com.guildup.monitoring.web;

import com.guildup.monitoring.logging.FailureLogContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/** The filter owns terminal HTTP error logging, including specialized advice responses. */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class GlobalExceptionHandler {
    public record Failure(String message, String requestId) {}

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Failure> handle(Exception failure, HttpServletRequest request) {
        if (org.springframework.web.util.DisconnectedClientHelper.isClientDisconnectedException(failure)) return null;
        int status = 500;
        String message = "서버 처리 중 오류가 발생했습니다.";
        if (failure instanceof org.springframework.http.converter.HttpMessageNotReadableException
                || failure instanceof org.springframework.validation.BindException
                || (failure instanceof org.springframework.beans.TypeMismatchException
                    && !(failure instanceof org.springframework.beans.ConversionNotSupportedException))) {
            status = 400;
        } else if (failure instanceof ErrorResponse error) {
            status = error.getStatusCode().value();
            if (status < 500 && failure instanceof ResponseStatusException expected && expected.getReason() != null)
                message = expected.getReason();
        } else {
            ResponseStatus annotation = AnnotationUtils.findAnnotation(failure.getClass(), ResponseStatus.class);
            if (annotation != null) {
                status = annotation.code().value();
                if (status < 500 && !annotation.reason().isBlank()) message = annotation.reason();
            }
        }
        if (status >= 500) FailureLogContext.setFailure(request, failure);
        else if (message.equals("서버 처리 중 오류가 발생했습니다.")) {
            message = switch (status) {
                case 401 -> "로그인 후 이용해 주세요.";
                case 403 -> "이 작업을 수행할 권한이 없습니다.";
                case 404 -> "요청한 데이터를 찾을 수 없습니다.";
                default -> "요청 내용을 확인해 주세요.";
            };
        }
        return ResponseEntity.status(status).body(new Failure(message,
                (String) request.getAttribute(RequestLogContextFilter.REQUEST_ID)));
    }
}
