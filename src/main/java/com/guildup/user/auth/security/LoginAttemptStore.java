package com.guildup.user.auth.security;

import java.util.List;

/** A future shared store must atomically reserve both limits, including an account verification lease. */
public interface LoginAttemptStore {
    enum Outcome { SUCCESS, INVALID_CREDENTIALS, ABORTED }
    enum Kind { REPEATED_FAILURE, RATE_LIMITED, IP_VOLUME, MULTI_ACCOUNT, SUSPECTED_ATTACK }
    record Signal(Kind kind, String scope, long requestCount, int failureCount, int distinctAccounts,
                  boolean rateLimited, long retryAfterSeconds) {}
    interface Permit {}
    record Decision(Permit permit, long retryAfterSeconds, List<Signal> signals) {
        public boolean allowed() { return permit != null; }
    }
    Decision begin(String accountId, String ipId);
    List<Signal> complete(Permit permit, Outcome outcome);
}
