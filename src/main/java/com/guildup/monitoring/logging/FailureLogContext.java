package com.guildup.monitoring.logging;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.WeakHashMap;

/** Marks the same propagated failure, without retaining it after its lifetime. */
public final class FailureLogContext {
    public static final String FAILURE = FailureLogContext.class.getName() + ".failure";
    private static final int MAX_MARKERS = 4096;
    private static final Set<Throwable> LOGGED = Collections.newSetFromMap(new WeakHashMap<>());
    private FailureLogContext() {}

    public static synchronized void markLogged(Throwable failure) {
        if (failure == null) return;
        if (LOGGED.size() >= MAX_MARKERS && !LOGGED.contains(failure)) {
            var existing = LOGGED.iterator();
            if (existing.hasNext()) { existing.next(); existing.remove(); }
        }
        LOGGED.add(failure);
    }

    public static synchronized boolean isLogged(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure; current != null && seen.add(current); current = current.getCause()) {
            if (LOGGED.contains(current)) return true;
        }
        return false;
    }

    public static void capture(Throwable failure) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)
            setFailure(attributes.getRequest(), failure);
    }

    public static void setFailure(HttpServletRequest request, Throwable failure) {
        request.setAttribute(FAILURE, failure);
    }
}
