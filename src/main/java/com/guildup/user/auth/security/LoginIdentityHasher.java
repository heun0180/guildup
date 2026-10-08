package com.guildup.user.auth.security;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;

/** Namespaced HMACs only; neither raw addresses nor credentials enter the store or events. */
@Component
public class LoginIdentityHasher {
    private final SecretKeySpec key;

    public LoginIdentityHasher(LoginProtectionProperties properties) {
        String configured = properties.getHmacSecret();
        byte[] secret;
        if (configured == null || configured.isBlank()) {
            secret = new byte[32];
            new SecureRandom().nextBytes(secret);
        } else {
            secret = configured.getBytes(StandardCharsets.UTF_8);
            if (secret.length < 32) throw new IllegalArgumentException("Login HMAC secret must contain at least 32 bytes");
        }
        key = new SecretKeySpec(secret, "HmacSHA256");
    }

    public String hash(String namespace, String value) {
        try {
            // Mac is not thread safe. A request-local instance avoids shared mutable cryptographic state.
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal((namespace + ":" + value).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException failure) {
            throw new IllegalStateException("Login identifier protection unavailable");
        }
    }
}
