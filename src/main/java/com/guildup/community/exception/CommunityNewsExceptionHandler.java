package com.guildup.community.exception;

import com.guildup.community.controller.CommunityNoticeController;
import com.guildup.community.controller.CommunityEventController;
import com.guildup.community.controller.CommunityNewsSummaryController;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/** 신규 소식 API에만 적용하며 기존 API의 오류 형식은 유지한다. */
@RestControllerAdvice(assignableTypes = {CommunityNoticeController.class, CommunityEventController.class,
        CommunityNewsSummaryController.class})
public class CommunityNewsExceptionHandler {
    public record ErrorResponse(String message) {}

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleStatus(ResponseStatusException exception) {
        String message = switch (exception.getStatusCode().value()) {
            case 401 -> "로그인 후 이용해 주세요.";
            case 403 -> "이 작업을 수행할 커뮤니티 권한이 없습니다.";
            case 404 -> "게시물을 찾을 수 없습니다.";
            default -> exception.getReason();
        };
        return ResponseEntity.status(exception.getStatusCode()).body(new ErrorResponse(message));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleInvalidBody(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(new ErrorResponse("입력한 종류와 날짜 형식을 확인해 주세요."));
    }
}
