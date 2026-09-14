package com.guildup.community.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/** 같은 Community의 전체 동기화와 사용자 단위 이벤트가 서로 상태를 덮어쓰지 않게 조정한다. */
@Component
public class CommunityMemberSyncLockManager {

    private final ConcurrentHashMap<Long, ReentrantReadWriteLock> communityLocks = new ConcurrentHashMap<>();
    private final Object[] memberLocks = new Object[256];

    public CommunityMemberSyncLockManager() {
        for (int index = 0; index < memberLocks.length; index++) {
            memberLocks[index] = new Object();
        }
    }

    /** 서로 다른 사용자는 병렬 처리하되 같은 Discord 사용자의 이벤트 순서는 직렬화한다. */
    public void withMemberUpdate(Long communityId, String discordUserId, Runnable action) {
        ReentrantReadWriteLock.ReadLock communityLock = lockFor(communityId).readLock();
        String key = communityId + ":" + discordUserId;
        Object memberLock = memberLocks[Math.floorMod(key.hashCode(), memberLocks.length)];
        communityLock.lock();
        try {
            synchronized (memberLock) {
                action.run();
            }
        } finally {
            communityLock.unlock();
        }
    }

    /** 한 Community의 snapshot 비교는 해당 Community의 다른 전체/실시간 반영과 겹치지 않는다. */
    public <T> T withReconciliation(Long communityId, Supplier<T> action) {
        ReentrantReadWriteLock.WriteLock lock = lockFor(communityId).writeLock();
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    private ReentrantReadWriteLock lockFor(Long communityId) {
        return communityLocks.computeIfAbsent(communityId, ignored -> new ReentrantReadWriteLock(true));
    }
}
