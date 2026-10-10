package com.guildup.developer.controller;

import com.guildup.developer.dto.DeveloperResponses;
import com.guildup.developer.dto.DeveloperUserResponses.*;
import com.guildup.developer.service.DeveloperUserQueryService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;

/** DeveloperAccessInterceptor가 모든 경로에서 활성 SYSTEM_ADMIN을 검증한다. */
@RestController
@RequestMapping("/api/developer/users")
public class DeveloperUserController {
    private final DeveloperUserQueryService queries;
    public DeveloperUserController(DeveloperUserQueryService queries) { this.queries = queries; }

    @ModelAttribute
    public void noStore(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }

    @GetMapping
    public DeveloperResponses.Page<Summary> users(@RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "ALL") String loginMethod,
            @RequestParam(defaultValue = "createdAt") String sort,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return queries.users(q, loginMethod, sort, direction, page, size);
    }

    @GetMapping("/statistics")
    public Statistics statistics() { return queries.statistics(); }

    @GetMapping("/{userId}")
    public Detail user(@PathVariable long userId) { return queries.user(userId); }
}
