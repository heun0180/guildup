package com.guildup.feedback.exception;

import com.guildup.community.exception.CommunityNotFoundException;
import com.guildup.feedback.controller.FeedbackController;
import com.guildup.feedback.controller.DeveloperFeedbackController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(assignableTypes = {FeedbackController.class, DeveloperFeedbackController.class})
public class FeedbackExceptionHandler {
    public record ErrorResponse(String message) {
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleStatus(ResponseStatusException exception) {
        if (exception.getStatusCode().value() >= 500) com.guildup.monitoring.logging.FailureLogContext.capture(exception);
        String message = switch (exception.getStatusCode().value()) {
            case 401 -> "로그인 후 이용해 주세요.";
            case 403 -> "문의 관리 권한이 없습니다.";
            default -> exception.getStatusCode().value() >= 500 ? "서버 처리 중 오류가 발생했습니다." : exception.getReason();
        };
        return ResponseEntity.status(exception.getStatusCode()).body(new ErrorResponse(message));
    }

    @ExceptionHandler(CommunityNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleCommunityNotFound(CommunityNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("커뮤니티를 찾을 수 없습니다."));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleInvalidBody(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(new ErrorResponse("문의 유형과 입력 내용을 확인해 주세요."));
    }

    @ExceptionHandler(org.springframework.orm.ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleConcurrentUpdate(Exception exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("문의가 변경되었습니다. 다시 열어 주세요."));
    }

    @ExceptionHandler(FeedbackMailException.class)
    public ResponseEntity<ErrorResponse> handleMailFailure(FeedbackMailException exception) {
        com.guildup.monitoring.logging.FailureLogContext.capture(exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("문의 전송에 실패했습니다."));
    }
}
