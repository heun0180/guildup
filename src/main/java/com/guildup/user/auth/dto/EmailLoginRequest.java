package com.guildup.user.auth.dto;

public record EmailLoginRequest(String email, String password) {
    @Override public String toString() { return "EmailLoginRequest[redacted]"; }
}
