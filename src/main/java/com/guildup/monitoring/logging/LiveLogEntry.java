package com.guildup.monitoring.logging;

import java.time.Instant;
import java.util.Map;

/** Strings only: retaining an entry never retains a Throwable, request, or session. */
public record LiveLogEntry(String id, Instant timestamp, String level, String category,
                           String thread, String message, String stackTrace, Map<String, String> context) {}
