package com.guildup.user.auth.dto;

public record SignupRequest(String email, String password, String passwordConfirmation, String nickname) {
    @Override public String toString() { return "SignupRequest[redacted]"; }
}
