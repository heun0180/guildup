package com.guildup.user.profile.controller;

import com.guildup.user.profile.dto.ProfileUpdateRequest;
import com.guildup.user.profile.service.AccountProfileService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/account/profile")
public class AccountProfileController {
    private final AccountProfileService profiles;

    public AccountProfileController(AccountProfileService profiles) { this.profiles = profiles; }

    @GetMapping
    public ResponseEntity<AccountProfileService.ProfileResponse> get(HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(profiles.get(request.getSession(false)));
    }

    @PatchMapping
    public ResponseEntity<AccountProfileService.ProfileResponse> update(@RequestBody ProfileUpdateRequest body,
                                                                        HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(profiles.update(request.getSession(false), body));
    }
}
