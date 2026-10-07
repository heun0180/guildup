package com.guildup.user.auth.dto;

public record CredentialRequest(String email, String password, String passwordConfirmation) {
    @Override public String toString() { return "CredentialRequest[redacted]"; }
}
