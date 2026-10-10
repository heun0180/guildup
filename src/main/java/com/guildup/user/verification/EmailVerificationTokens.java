package com.guildup.user.verification;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

public final class EmailVerificationTokens {
    private static final SecureRandom RANDOM = new SecureRandom();
    private EmailVerificationTokens() {}
    public static String generate() {
        byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    public static boolean validFormat(String raw) { return raw != null && raw.matches("[A-Za-z0-9_-]{43}"); }
    public static String hash(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
}
