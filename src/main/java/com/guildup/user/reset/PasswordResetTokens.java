package com.guildup.user.reset;

import com.guildup.user.verification.EmailVerificationTokens;

/** Reuse 256-bit SecureRandom tokens; domain-separated hashes cannot match email-verification hashes. */
public final class PasswordResetTokens {
    private PasswordResetTokens() {}
    public static String generate() { return EmailVerificationTokens.generate(); }
    public static boolean validFormat(String raw) { return EmailVerificationTokens.validFormat(raw); }
    public static String hash(String raw) { return EmailVerificationTokens.hash("PASSWORD_RESET:" + raw); }
}
