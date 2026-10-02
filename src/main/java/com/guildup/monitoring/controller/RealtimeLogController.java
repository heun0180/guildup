package com.guildup.monitoring.controller;

import com.guildup.monitoring.service.RealtimeLogStreamService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/developer/monitoring/logs")
public class RealtimeLogController {
    private final RealtimeLogStreamService streams;
    public RealtimeLogController(RealtimeLogStreamService streams) { this.streams = streams; }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(HttpServletRequest request, HttpServletResponse response,
                             @RequestHeader(name = "Last-Event-ID", required = false) String lastId) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Accel-Buffering", "no");
        return streams.open(request.getSession(false), lastId, response::isCommitted);
    }
}
