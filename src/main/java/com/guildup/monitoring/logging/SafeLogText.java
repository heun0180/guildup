package com.guildup.monitoring.logging;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared redaction before file encoding and before retaining live-log strings. */
public final class SafeLogText {
    private static final int MAX_INSPECTED_CHARACTERS = 8192;
    private static final String REDACTED = "[REDACTED]";
    private static final String TRUNCATED = "…[truncated]";
    private static final Pattern HEADERS = Pattern.compile("(?im)(\\b(?:Authorization|Proxy-Authorization|Cookie|Set-Cookie)\\s*[:=]\\s*)[^\\r\\n]+(?=\\r?$)");
    private static final Pattern VALUES = Pattern.compile("(?<![\\w.-])[\\\"']?([\\w.-]++)[\\\"']?\\s*[:=]\\s*");
    private static final Pattern SENSITIVE_VALUE_KEY = Pattern.compile("(?i)password|passwd|secret|api[-_]?key|token|authorization|cookie|session[-_]?id|JSESSIONID");
    private static final Pattern BEARER = Pattern.compile("(?i)Bearer\\s+[^\\s,;\\\"'\\]}]+" );
    private static final Pattern JWT = Pattern.compile("\\beyJ[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]*){0,2}");
    private static final Pattern EMAIL = Pattern.compile("(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]++@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern SQL_DETAIL = Pattern.compile("(?im)^(\\s*(?:Detail|DETAIL|Where|WHERE):).*$");
    private static final Pattern SQL_BODY = Pattern.compile("(?is)(?:SQL \\[|SQL statement \\[|could not execute statement \\[|JDBC exception executing SQL \\[).*?(?:\\]|$)");
    private static final Pattern SQL_QUERY = Pattern.compile("(?is)\\[(?:\\s*(?:select|insert|update|delete|with|alter|create)\\b).*?(?:\\]|$)");
    private static final Pattern HTTP_BODY = Pattern.compile("(?is)(\\b(?:[45]\\d\\d|response body)\\b[^\\r\\n]{0,120}?[:=]\\s*)(?=\\\")");
    private static final Pattern SENSITIVE_PATH = Pattern.compile("(?i)(/(?:oauth/)?(?:results?|installations?|install-tokens?)/)[^/?\\s]+" );
    private static final Pattern SENSITIVE_HINT = Pattern.compile("(?i)password|passwd|secret|api[-_]?key|token|authorization|cookie|session[-_]?id|JSESSIONID|bearer|eyJ|@|SQL|Detail:|Where:|\\[\\s*(?:select|insert|update|delete|with|alter|create)|\\b[45]\\d\\d\\b|response body|/(?:oauth/)?(?:results?|installations?|install-tokens?)/");
    private static final List<String> CONFIGURED_SECRETS = System.getenv().entrySet().stream()
            .filter(entry -> Pattern.compile("(?i).*(?:password|passwd|secret|api_?key|token).*").matcher(entry.getKey()).matches())
            .map(java.util.Map.Entry::getValue).filter(value -> value.length() >= 8).distinct().toList();

    private SafeLogText() {}

    public static String redact(String text) {
        if (text == null) return "";
        String safe = text;
        for (String secret : CONFIGURED_SECRETS) safe = safe.replace(secret, "[REDACTED]");
        if (!SENSITIVE_HINT.matcher(safe).find()) return safe;
        safe = HEADERS.matcher(safe).replaceAll("$1[REDACTED]");
        safe = BEARER.matcher(safe).replaceAll("Bearer [REDACTED]");
        safe = redactValues(safe, VALUES);
        safe = JWT.matcher(safe).replaceAll("[REDACTED]");
        safe = SQL_DETAIL.matcher(safe).replaceAll("$1 [REDACTED]");
        safe = SQL_BODY.matcher(safe).replaceAll("SQL [REDACTED]");
        safe = SQL_QUERY.matcher(safe).replaceAll("[SQL REDACTED]");
        safe = redactValues(safe, HTTP_BODY);
        safe = SENSITIVE_PATH.matcher(safe).replaceAll("$1[REDACTED]");
        return EMAIL.matcher(safe).replaceAll("[EMAIL]");
    }

    public static String limited(String text, int limit) {
        if (limit < 0) throw new IllegalArgumentException("limit must not be negative");
        if (text == null) return "";
        boolean inputTruncated = text.length() > MAX_INSPECTED_CHARACTERS;
        String inspected = inputTruncated ? text.substring(0, MAX_INSPECTED_CHARACTERS) : text;
        if (inputTruncated) inspected = redactPartialConfiguredSecret(inspected);
        String safe = redact(inspected);
        if (safe.length() > limit) return safe.substring(0, limit) + TRUNCATED;
        return inputTruncated ? safe + TRUNCATED : safe;
    }

    /** A cut or escaped quoted value must never fall back to partially masking an unquoted word. */
    private static String redactValues(String text, Pattern prefixPattern) {
        Matcher matcher = prefixPattern.matcher(text);
        StringBuilder result = null;
        int copied = 0;
        while (matcher.find()) {
            if (prefixPattern == VALUES && !SENSITIVE_VALUE_KEY.matcher(matcher.group(1)).find()) continue;
            int start = matcher.end();
            int end = valueEnd(text, start);
            if (end == start) continue;
            if (result == null) result = new StringBuilder(text.length());
            result.append(text, copied, start).append(REDACTED);
            copied = end;
            matcher.region(end, text.length());
        }
        return result == null ? text : result.append(text, copied, text.length()).toString();
    }

    private static int valueEnd(String text, int start) {
        if (start == text.length()) return start;
        if (text.startsWith(REDACTED, start)) return start + REDACTED.length();
        char quote = text.charAt(start);
        if (quote == '"' || quote == '\'') {
            for (int i = start + 1; i < text.length(); i++) {
                char next = text.charAt(i);
                if (next == '\\') i++;
                else if (next == quote) return i + 1;
            }
            return text.length();
        }
        int end = start;
        while (end < text.length()) {
            char next = text.charAt(end);
            if (Character.isWhitespace(next) || ",;&}]".indexOf(next) >= 0) break;
            end++;
        }
        return end;
    }

    private static String redactPartialConfiguredSecret(String prefix) {
        for (String secret : CONFIGURED_SECRETS) {
            String start = secret.substring(0, 8);
            int candidate = prefix.lastIndexOf(start);
            while (candidate >= 0) {
                int retained = prefix.length() - candidate;
                if (retained < secret.length() && secret.regionMatches(0, prefix, candidate, retained)) {
                    prefix = prefix.substring(0, candidate) + REDACTED;
                    break;
                }
                candidate = prefix.lastIndexOf(start, candidate - 1);
            }
        }
        return prefix;
    }

    public static String endpoint(String uri) {
        if (uri == null) return "";
        String path = uri.substring(0, Math.min(uri.length(), MAX_INSPECTED_CHARACTERS));
        int query = path.indexOf('?');
        if (query >= 0) path = path.substring(0, query);
        return limited(SENSITIVE_PATH.matcher(path).replaceAll("$1[REDACTED]"), 300);
    }
}
