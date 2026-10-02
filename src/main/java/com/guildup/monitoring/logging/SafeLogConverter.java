package com.guildup.monitoring.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.pattern.CompositeConverter;

public class SafeLogConverter extends CompositeConverter<ILoggingEvent> {
    @Override protected String transform(ILoggingEvent event, String rendered) {
        return SafeLogText.redact(rendered);
    }
}
