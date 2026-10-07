package com.guildup.user.auth.controller;

import com.guildup.user.auth.service.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/account")
public class AccountWithdrawalController {
    private final AccountWithdrawalService withdrawal;
    private final AuthSessionService sessions;
    public AccountWithdrawalController(AccountWithdrawalService withdrawal, AuthSessionService sessions) {
        this.withdrawal = withdrawal; this.sessions = sessions;
    }

    @GetMapping("/withdrawal/check")
    public ResponseEntity<AccountWithdrawalService.CheckResponse> check(HttpServletRequest request) {
        var session = request.getSession(false);
        CurrentUserSession.requireUserId(session);
        synchronized (session) {
            var id = sessions.requireUser(session).getId();
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(withdrawal.check(id, proof(session)));
        }
    }

    @PostMapping("/withdrawal/verify")
    public ResponseEntity<Void> verify(@RequestBody PasswordRequest body, HttpServletRequest request) {
        var session = request.getSession(false);
        CurrentUserSession.requireUserId(session);
        synchronized (session) {
            var id = sessions.requireUser(session).getId();
            session.removeAttribute(WithdrawalVerification.ATTRIBUTE);
            session.setAttribute(WithdrawalVerification.ATTRIBUTE, withdrawal.verifyPassword(id, body.password()));
            return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
        }
    }

    @DeleteMapping
    public ResponseEntity<Void> withdraw(@RequestBody Confirmation body, HttpServletRequest request) {
        var session = request.getSession(false);
        CurrentUserSession.requireUserId(session);
        synchronized (session) {
            var id = sessions.requireUser(session).getId();
            withdrawal.withdraw(id, proof(session), body.acknowledged());
            // Service proxy has committed; DB failure leaves the current session usable.
            sessions.revokeUser(id);
            sessions.logout(request);
            return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
        }
    }

    private WithdrawalVerification proof(HttpSession session) {
        try { return session.getAttribute(WithdrawalVerification.ATTRIBUTE) instanceof WithdrawalVerification value ? value : null; }
        catch (IllegalStateException ignored) { return null; }
    }
    public record PasswordRequest(String password) {
        @Override public String toString() { return "PasswordRequest[redacted]"; }
    }
    public record Confirmation(boolean acknowledged) {}
}
