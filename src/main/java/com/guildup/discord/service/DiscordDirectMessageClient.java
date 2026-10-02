package com.guildup.discord.service;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.exceptions.RateLimitedException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletionException;

/** JDA를 통해 개인 채널을 열고 메시지 한 건을 전송한다. */
@Component
public class DiscordDirectMessageClient {

    private final JDA jda;

    public DiscordDirectMessageClient(JDA jda) {
        this.jda = jda;
    }

    /** JDA의 rate limit 큐를 사용하며 이 요청이 끝날 때까지 기다려 순차 발송을 보장한다. */
    public void send(String discordUserId, String message) {
        jda.openPrivateChannelById(discordUserId)
                .flatMap(channel -> channel.sendMessage(message))
                .submit()
                .join();
    }

    /** Discord가 명시한 DM 차단/비허용 응답인지 판별한다. */
    public boolean isDmNotAvailable(Throwable throwable) {
        Throwable cause = unwrap(throwable);
        if (!(cause instanceof ErrorResponseException exception)) {
            return false;
        }
        return exception.getErrorResponse() == ErrorResponse.CANNOT_SEND_TO_USER
                || exception.getErrorResponse() == ErrorResponse.INVALID_DM_ACTION
                || exception.getErrorResponse() == ErrorResponse.EXPLICIT_CONTENT_CANNOT_SEND_TO_RECIPIENT;
    }

    /** 응답 본문/토큰을 출력하지 않고 운영 로그에 필요한 Discord 상태만 추출한다. */
    public Integer responseStatus(Throwable throwable) {
        Throwable cause = unwrap(throwable);
        if (cause instanceof RateLimitedException) return 429;
        if (cause instanceof ErrorResponseException exception && exception.getResponse() != null) {
            return exception.getResponse().code;
        }
        return null;
    }

    private Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
