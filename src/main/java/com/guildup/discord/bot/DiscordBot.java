package com.guildup.discord.bot;

import com.guildup.community.service.CommunityMemberReconciliationQueue;
import com.guildup.discord.service.DiscordVoiceRecoveryService;
import jakarta.annotation.PreDestroy;
import net.dv8tion.jda.api.JDA;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

/** 애플리케이션 시작/종료 생명주기에 Discord JDA 클라이언트를 연결한다. */
@Component
public class DiscordBot {

    private static final Logger log = LoggerFactory.getLogger(DiscordBot.class);

    private final JDA jda;
    private final DiscordVoiceRecoveryService voiceRecoveryService;
    private final CommunityMemberReconciliationQueue memberReconciliationQueue;
    private final TaskExecutor recoveryBootstrapExecutor;

    public DiscordBot(
            JDA jda,
            DiscordVoiceRecoveryService voiceRecoveryService,
            CommunityMemberReconciliationQueue memberReconciliationQueue,
            @Qualifier("discordRecoveryBootstrapExecutor") TaskExecutor recoveryBootstrapExecutor
    ) {
        this.jda = jda;
        this.voiceRecoveryService = voiceRecoveryService;
        this.memberReconciliationQueue = memberReconciliationQueue;
        this.recoveryBootstrapExecutor = recoveryBootstrapExecutor;
    }

    /** 웹 애플리케이션 준비를 막지 않고 Discord 복구를 백그라운드에서 시작한다. */
    @EventListener(ApplicationReadyEvent.class)
    public void startRecovery() {
        log.info("Discord Bot login successful - name: {}", jda.getSelfUser().getName());
        recoveryBootstrapExecutor.execute(() -> {
            try {
                voiceRecoveryService.reconcile(jda);
            } catch (RuntimeException exception) {
                log.error("Discord voice recovery failed", exception);
            }
            try {
                memberReconciliationQueue.enqueueAll();
            } catch (RuntimeException exception) {
                log.error("Discord member reconciliation queue bootstrap failed", exception);
            }
        });
    }

    /** 애플리케이션 종료 전에 Discord Gateway 연결을 정상적으로 닫는다. */
    @PreDestroy
    public void shutdown() {
        jda.shutdown();
    }
}
