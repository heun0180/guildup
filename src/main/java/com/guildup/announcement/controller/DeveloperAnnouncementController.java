package com.guildup.announcement.controller;

import com.guildup.announcement.dto.PlatformAnnouncementRequest;
import com.guildup.announcement.dto.PlatformAnnouncementResponses.*;
import com.guildup.announcement.service.PlatformAnnouncementService;
import com.guildup.user.auth.service.CurrentUserSession;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** 기존 DeveloperAccessInterceptor + 서비스 계층 SYSTEM_ADMIN 검사로 보호한다. */
@RestController
@RequestMapping("/api/developer/announcements")
public class DeveloperAnnouncementController {
    private final PlatformAnnouncementService service;
    public DeveloperAnnouncementController(PlatformAnnouncementService service) { this.service = service; }

    @GetMapping
    public Page list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size, HttpSession session) {
        return service.adminList(CurrentUserSession.requireUserId(session), page, size);
    }
    @GetMapping("/{id}")
    public Detail get(@PathVariable Long id, HttpSession session) {
        return service.adminGet(CurrentUserSession.requireUserId(session), id);
    }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public Detail create(@RequestBody PlatformAnnouncementRequest request, HttpSession session) {
        return service.create(CurrentUserSession.requireUserId(session), request);
    }
    @PutMapping("/{id}")
    public Detail update(@PathVariable Long id, @RequestBody PlatformAnnouncementRequest request, HttpSession session) {
        return service.update(CurrentUserSession.requireUserId(session), id, request);
    }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, HttpSession session) {
        service.delete(CurrentUserSession.requireUserId(session), id);
    }
}
