package com.guildup.monitoring.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class RealtimeLogAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {
    private final RealtimeLogBuffer buffer;
    private Logger root;
    public RealtimeLogAppender(RealtimeLogBuffer buffer) { this.buffer = buffer; }

    @PostConstruct public void attach() {
        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext loggerContext) {
            setContext(loggerContext);
            setName("GUILDUP_LIVE");
            start();
            root = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
            root.addAppender(this);
        }
    }

    @Override protected void append(ILoggingEvent event) {
        try { buffer.append(event); }
        catch (RuntimeException failure) { addWarn("Live log entry dropped", failure); }
    }

    @PreDestroy public void detach() {
        if (root != null) root.detachAppender(this);
        stop();
    }
}
