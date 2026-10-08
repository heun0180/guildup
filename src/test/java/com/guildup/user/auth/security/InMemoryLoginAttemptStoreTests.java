package com.guildup.user.auth.security;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static com.guildup.user.auth.security.LoginAttemptStore.*;

class InMemoryLoginAttemptStoreTests {
    final MutableClock clock = new MutableClock();
    final LoginProtectionProperties policy = new LoginProtectionProperties();
    InMemoryLoginAttemptStore store() { return new InMemoryLoginAttemptStore(policy, clock); }

    @Test void progressiveCooldownIsShortAndRejectedRequestsNeverExtendIt() {
        var store = store();
        for (int i = 0; i < 5; i++) fail(store, "account", "ip");
        assertThat(store.begin("account", "ip").retryAfterSeconds()).isEqualTo(2);
        clock.advance(Duration.ofSeconds(1));
        for (int i = 0; i < 10; i++) assertThat(store.begin("account", "ip").retryAfterSeconds()).isEqualTo(1);
        clock.advance(Duration.ofSeconds(1));
        fail(store, "account", "ip");
        assertThat(store.begin("account", "ip").retryAfterSeconds()).isEqualTo(4);
        for (int expected : new int[]{8, 16, 30, 30}) {
            clock.advance(Duration.ofSeconds(30)); fail(store, "account", "ip");
            assertThat(store.begin("account", "ip").retryAfterSeconds()).isEqualTo(expected);
        }
    }

    @Test void successClearsFailuresButDoesNotResetIpBudget() {
        var store = store();
        for (int i = 0; i < 4; i++) fail(store, "account", "ip");
        var success = store.begin("account", "ip");
        assertThat(store.complete(success.permit(), Outcome.SUCCESS)).isEmpty();
        assertThat(store.accountSize()).isZero();
        for (int i = 0; i < 4; i++) fail(store, "account", "ip");
        assertThat(store.begin("account", "ip").allowed()).isTrue();
    }

    @Test void failureWindowExpiresEvenWithContinuingRejectedRequests() {
        var store = store();
        for (int i = 0; i < 5; i++) fail(store, "account", "ip");
        clock.advance(Duration.ofMinutes(15));
        for (int i = 0; i < 4; i++) fail(store, "account", "ip");
        assertThat(store.begin("account", "ip").allowed()).isTrue();
    }

    @Test void fixedIpWindowsCountAttemptsAcrossAccountsAndDoNotSlideOnDenial() {
        policy.setIpShortLimit(3); policy.setIpLongLimit(5);
        var store = store();
        for (int i = 0; i < 3; i++) succeed(store, "account" + i, "ip");
        assertThat(store.begin("fourth", "ip").retryAfterSeconds()).isEqualTo(60);
        clock.advance(Duration.ofSeconds(59));
        assertThat(store.begin("fifth", "ip").retryAfterSeconds()).isEqualTo(1);
        clock.advance(Duration.ofSeconds(1));
        // Denials count toward the longer window too; success does not reset an IP.
        assertThat(store.begin("sixth", "ip").retryAfterSeconds()).isEqualTo(840);
        clock.advance(Duration.ofMinutes(14));
        assertThat(store.begin("after-expiry", "ip").allowed()).isTrue();
    }

    @Test void detectsSprayingUnknownAccountsAndWaitsOnlyForShortCooldown() {
        policy.setSprayAccounts(3); policy.setSprayFailures(3);
        var store = store();
        fail(store, "unknown1", "ip"); fail(store, "unknown2", "ip");
        var decision = store.begin("unknown3", "ip");
        assertThat(store.complete(decision.permit(), Outcome.INVALID_CREDENTIALS))
                .anyMatch(signal -> signal.kind() == Kind.MULTI_ACCOUNT && signal.distinctAccounts() == 3 && signal.rateLimited());
        assertThat(store.begin("another", "ip").retryAfterSeconds()).isEqualTo(30);
        clock.advance(Duration.ofSeconds(30));
        assertThat(store.begin("another", "ip").allowed()).isTrue();
    }

    @Test void sharedIpWithSuccessfulLoginsDoesNotTriggerSprayHeuristic() {
        policy.setSprayAccounts(3); policy.setSprayFailures(3);
        var store = store();
        for (int i = 0; i < 10; i++) succeed(store, "member" + i, "shared");
        for (int i = 0; i < 3; i++) fail(store, "mistake" + i, "shared");
        assertThat(store.begin("member10", "shared").allowed()).isTrue();
    }

    @Test void parallelRequestsToOneAccountReserveOnlyOneVerificationAcrossIps() throws Exception {
        var store = store();
        try (var pool = Executors.newFixedThreadPool(16)) {
            var start = new CountDownLatch(1);
            List<Future<Decision>> futures = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                String ip = "ip" + i;
                futures.add(pool.submit(() -> { start.await(); return store.begin("same", ip); }));
            }
            start.countDown();
            List<Decision> decisions = new ArrayList<>();
            for (var future : futures) decisions.add(future.get(5, TimeUnit.SECONDS));
            assertThat(decisions.stream().filter(Decision::allowed)).hasSize(1);
            var permit = decisions.stream().filter(Decision::allowed).findFirst().orElseThrow().permit();
            store.complete(permit, Outcome.INVALID_CREDENTIALS);
            store.complete(permit, Outcome.INVALID_CREDENTIALS); // Completion is idempotent.
            assertThat(store.begin("same", "new-ip").allowed()).isTrue();
        }
    }

    @Test void parallelAccountsCannotExceedIpOrGlobalVerificationBudget() throws Exception {
        policy.setIpShortLimit(3); policy.setMaxConcurrentVerifications(2);
        var store = store();
        var first = store.begin("one", "ip1"); var second = store.begin("two", "ip2");
        assertThat(store.begin("three", "ip3").signals()).anyMatch(signal -> signal.scope().equals("VERIFICATION_CAPACITY"));
        store.complete(first.permit(), Outcome.ABORTED);
        assertThat(store.begin("three", "ip3").allowed()).isTrue();
        store.complete(second.permit(), Outcome.ABORTED);
        clock.advance(Duration.ofMinutes(1));
        var shared = store();
        try (var pool = Executors.newFixedThreadPool(16)) {
            List<Callable<Decision>> tasks = new ArrayList<>();
            for (int i = 0; i < 16; i++) { String account = "a" + i; tasks.add(() -> shared.begin(account, "same-ip")); }
            var decisions = pool.invokeAll(tasks);
            long allowed = 0;
            for (var future : decisions) if (future.get().allowed()) allowed++;
            assertThat(allowed).isEqualTo(2);
        }
    }

    @Test void boundedStoresFailClosedAndCleanExpiredRecordsWithoutEvictingActiveReservations() {
        policy.setMaxAccounts(1); policy.setMaxIps(1);
        var store = store();
        var first = store.begin("one", "ip");
        assertThat(store.begin("two", "ip").allowed()).isFalse();
        assertThat(store.begin("one", "other-ip").allowed()).isFalse();
        clock.advance(Duration.ofMinutes(16)); store.cleanup();
        assertThat(store.accountSize()).isEqualTo(1); assertThat(store.ipSize()).isEqualTo(1);
        store.complete(first.permit(), Outcome.ABORTED); store.cleanup();
        assertThat(store.accountSize()).isZero(); assertThat(store.ipSize()).isZero();
        assertThat(store.begin("two", "other-ip").allowed()).isTrue();
    }

    @Test void rejectionsAndRepeatFailuresAreSampledPerIdentity() {
        var store = store();
        for (int i = 0; i < 5; i++) fail(store, "account", "ip");
        assertThat(store.begin("account", "ip").signals()).hasSize(1);
        assertThat(store.begin("account", "ip").signals()).isEmpty();
    }

    @Test void restartResetsRestrictionsAndPolicyRejectsLongAccountLockout() {
        var store = store();
        for (int i = 0; i < 5; i++) fail(store, "account", "ip");
        assertThat(store().begin("account", "ip").allowed()).isTrue();
        policy.setAccountMaxDelay(Duration.ofHours(1));
        assertThatThrownBy(this::store).isInstanceOf(IllegalArgumentException.class);
    }

    private void fail(InMemoryLoginAttemptStore store, String account, String ip) {
        var decision = store.begin(account, ip); assertThat(decision.allowed()).isTrue();
        store.complete(decision.permit(), Outcome.INVALID_CREDENTIALS);
    }
    private void succeed(InMemoryLoginAttemptStore store, String account, String ip) {
        var decision = store.begin(account, ip); assertThat(decision.allowed()).isTrue();
        store.complete(decision.permit(), Outcome.SUCCESS);
    }
    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-08T00:00:00Z");
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
