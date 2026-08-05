package com.med.qa.memory.lock;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import com.med.qa.domain.entity.ChatSessionDO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionLockServiceTest {

    private static final String TENANT = "hosp-01";
    private static final String DEPT = "cardiology";
    private static final String SESSION = "sess-7f3c";
    private static final String KEY = "med:lock:chat:hosp-01:cardiology:sess-7f3c";

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock lock;

    private MedLockProperties properties;

    private SessionLockService service;

    @BeforeEach
    void setUp() {
        properties = new MedLockProperties();
        service = new SessionLockService(redissonClient, properties);
    }

    private static ChatSessionDO session() {
        ChatSessionDO session = new ChatSessionDO();
        session.setTenantId(TENANT);
        session.setDeptId(DEPT);
        session.setSessionId(SESSION);
        return session;
    }

    // ------------------------------------------------------------------ key schema

    @Test
    @DisplayName("lock key follows med:lock:chat:{tenant}:{dept}:{session}")
    void lockKeyFollowsSpec() {
        assertThat(service.lockKey(TENANT, DEPT, SESSION)).isEqualTo(KEY);
        assertThat(KEY).startsWith(SessionLockService.KEY_PREFIX);
    }

    @Test
    @DisplayName("blank identity segments are rejected to avoid cross-session lock collisions")
    void lockKeyRejectsBlankSegments() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.lockKey(" ", DEPT, SESSION))
                .withMessageContaining("tenantId");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.lockKey(TENANT, null, SESSION))
                .withMessageContaining("deptId");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.lockKey(TENANT, DEPT, ""))
                .withMessageContaining("sessionId");
        verifyNoInteractions(redissonClient);
    }

    @Test
    @DisplayName("the entity overload derives the same key")
    void lockKeyFromEntity() {
        assertThat(service.lockKey(session())).isEqualTo(KEY);
    }

    @Test
    @DisplayName("a null session entity is a programming error")
    void lockKeyRejectsNullSession() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.lockKey((ChatSessionDO) null))
                .withMessageContaining("session");
    }

    // ------------------------------------------------------------------ happy paths

    @Test
    @DisplayName("executeLocked runs the action under the lock and releases it afterwards")
    void executeLockedRunsAndReleases() throws InterruptedException {
        when(redissonClient.getLock(KEY)).thenReturn(lock);
        when(lock.tryLock(3000L, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        String result = service.executeLocked(TENANT, DEPT, SESSION, () -> "answer");

        assertThat(result).isEqualTo("answer");
        InOrder order = inOrder(lock);
        order.verify(lock).tryLock(3000L, TimeUnit.MILLISECONDS);
        order.verify(lock).unlock();
    }

    @Test
    @DisplayName("the default zero lease delegates renewal to the redisson watchdog")
    void watchdogVariantOmitsLeaseTime() throws InterruptedException {
        when(redissonClient.getLock(KEY)).thenReturn(lock);
        when(lock.tryLock(3000L, TimeUnit.MILLISECONDS)).thenReturn(true);

        service.executeLocked(TENANT, DEPT, SESSION, () -> null);

        verify(lock).tryLock(3000L, TimeUnit.MILLISECONDS);
        verify(lock, never()).tryLock(anyLong(), anyLong(), any(TimeUnit.class));
    }

    @Test
    @DisplayName("a configured lease time switches to the fixed-lease acquisition")
    void fixedLeaseVariantPassesLeaseTime() throws InterruptedException {
        properties.setWaitTime(Duration.ofSeconds(1));
        properties.setLeaseTime(Duration.ofSeconds(10));
        when(redissonClient.getLock(KEY)).thenReturn(lock);
        when(lock.tryLock(1000L, 10_000L, TimeUnit.MILLISECONDS)).thenReturn(true);

        service.executeLocked(TENANT, DEPT, SESSION, () -> null);

        verify(lock).tryLock(1000L, 10_000L, TimeUnit.MILLISECONDS);
        verify(lock, never()).tryLock(anyLong(), any(TimeUnit.class));
    }

    @Test
    @DisplayName("the entity overload locks the very same key")
    void executeLockedWithEntity() throws InterruptedException {
        when(redissonClient.getLock(KEY)).thenReturn(lock);
        when(lock.tryLock(3000L, TimeUnit.MILLISECONDS)).thenReturn(true);

        assertThat(service.executeLocked(session(), () -> 42)).isEqualTo(42);
    }

    @Test
    @DisplayName("runLocked guards void actions")
    void runLockedGuardsVoidAction() throws InterruptedException {
        when(redissonClient.getLock(KEY)).thenReturn(lock);
        when(lock.tryLock(3000L, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        AtomicInteger executions = new AtomicInteger();

        service.runLocked(TENANT, DEPT, SESSION, executions::incrementAndGet);
        service.runLocked(session(), executions::incrementAndGet);

        assertThat(executions).hasValue(2);
        verify(lock, org.mockito.Mockito.times(2)).unlock();
    }

    // ------------------------------------------------------------------ failure paths

    @Test
    @DisplayName("a busy session is reported as SESSION_LOCKED and the action never runs")
    void busySessionIsRejected() throws InterruptedException {
        when(redissonClient.getLock(KEY)).thenReturn(lock);
        when(lock.tryLock(3000L, TimeUnit.MILLISECONDS)).thenReturn(false);
        AtomicInteger executions = new AtomicInteger();

        assertThatThrownBy(() -> service.executeLocked(TENANT, DEPT, SESSION, executions::incrementAndGet))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.SESSION_LOCKED);

        assertThat(executions).hasValue(0);
        verify(lock, never()).unlock();
    }

    @Test
    @DisplayName("a redis outage during acquisition fails loudly as STORAGE_ERROR")
    void redisOutageDuringAcquireFailsLoudly() throws InterruptedException {
        when(redissonClient.getLock(KEY)).thenReturn(lock);
        when(lock.tryLock(3000L, TimeUnit.MILLISECONDS))
                .thenThrow(new RedisException("connection refused"));

        assertThatThrownBy(() -> service.executeLocked(TENANT, DEPT, SESSION, () -> "x"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining(KEY)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.STORAGE_ERROR);
    }

    @Test
    @DisplayName("an interrupt restores the flag and surfaces INTERNAL_ERROR")
    void interruptRestoresFlag() throws InterruptedException {
        when(redissonClient.getLock(KEY)).thenReturn(lock);
        when(lock.tryLock(3000L, TimeUnit.MILLISECONDS))
                .thenThrow(new InterruptedException("interrupted"));

        try {
            assertThatThrownBy(() -> service.executeLocked(TENANT, DEPT, SESSION, () -> "x"))
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INTERNAL_ERROR);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            // clear the flag so it cannot leak into the next test on this thread
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("an exception thrown by the guarded action still releases the lock")
    void actionFailureStillReleasesLock() throws InterruptedException {
        when(redissonClient.getLock(KEY)).thenReturn(lock);
        when(lock.tryLock(3000L, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        assertThatThrownBy(() -> service.executeLocked(TENANT, DEPT, SESSION, () -> {
            throw new IllegalStateException("write failed");
        })).isInstanceOf(IllegalStateException.class).hasMessage("write failed");

        verify(lock).unlock();
    }

    @Test
    @DisplayName("a lock already lost to the lease expiry is not unlocked again")
    void doesNotUnlockWhenLeaseAlreadyExpired() throws InterruptedException {
        when(redissonClient.getLock(KEY)).thenReturn(lock);
        when(lock.tryLock(3000L, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(false);

        assertThat(service.executeLocked(TENANT, DEPT, SESSION, () -> "done")).isEqualTo("done");

        verify(lock, never()).unlock();
    }

    @Test
    @DisplayName("a failed release does not mask the result of the completed action")
    void releaseFailureDoesNotMaskResult() throws InterruptedException {
        when(redissonClient.getLock(KEY)).thenReturn(lock);
        when(lock.tryLock(3000L, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        doThrow(new RedisException("connection reset")).when(lock).unlock();

        assertThat(service.executeLocked(TENANT, DEPT, SESSION, () -> "persisted")).isEqualTo("persisted");
    }

    @Test
    @DisplayName("a null action is a programming error on every overload")
    void nullActionIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.executeLocked(TENANT, DEPT, SESSION, null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.executeLocked(session(), null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.runLocked(TENANT, DEPT, SESSION, null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.runLocked(session(), null));
        verifyNoInteractions(redissonClient);
    }

    // ------------------------------------------------------------------ inspection helpers

    @Test
    @DisplayName("isLocked reflects the cluster-wide lock state")
    void isLockedDelegates() {
        when(redissonClient.getLock(KEY)).thenReturn(lock);
        when(lock.isLocked()).thenReturn(true, false);

        assertThat(service.isLocked(TENANT, DEPT, SESSION)).isTrue();
        assertThat(service.isLocked(TENANT, DEPT, SESSION)).isFalse();
    }

    @Test
    @DisplayName("isLocked surfaces a redis outage as STORAGE_ERROR")
    void isLockedFailsLoudly() {
        when(redissonClient.getLock(KEY)).thenReturn(lock);
        when(lock.isLocked()).thenThrow(new RedisException("down"));

        assertThatThrownBy(() -> service.isLocked(TENANT, DEPT, SESSION))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.STORAGE_ERROR);
    }

    @Test
    @DisplayName("isHeldByCurrentThread reflects ownership")
    void isHeldByCurrentThreadDelegates() {
        when(redissonClient.getLock(KEY)).thenReturn(lock);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        assertThat(service.isHeldByCurrentThread(TENANT, DEPT, SESSION)).isTrue();
    }

    @Test
    @DisplayName("isHeldByCurrentThread surfaces a redis outage as STORAGE_ERROR")
    void isHeldByCurrentThreadFailsLoudly() {
        when(redissonClient.getLock(KEY)).thenReturn(lock);
        when(lock.isHeldByCurrentThread()).thenThrow(new RedisException("down"));

        assertThatThrownBy(() -> service.isHeldByCurrentThread(TENANT, DEPT, SESSION))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.STORAGE_ERROR);
    }

    @Test
    @DisplayName("forceUnlock reports whether a stale lock was actually dropped")
    void forceUnlockDelegates() {
        when(redissonClient.getLock(KEY)).thenReturn(lock);
        when(lock.forceUnlock()).thenReturn(true, false);

        assertThat(service.forceUnlock(TENANT, DEPT, SESSION)).isTrue();
        assertThat(service.forceUnlock(TENANT, DEPT, SESSION)).isFalse();
    }

    @Test
    @DisplayName("forceUnlock surfaces a redis outage as STORAGE_ERROR")
    void forceUnlockFailsLoudly() {
        when(redissonClient.getLock(KEY)).thenReturn(lock);
        when(lock.forceUnlock()).thenThrow(new RedisException("down"));

        assertThatThrownBy(() -> service.forceUnlock(TENANT, DEPT, SESSION))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.STORAGE_ERROR);
    }

    @Test
    @DisplayName("inspection helpers validate the identity segments too")
    void inspectionHelpersValidateSegments() {
        assertThatIllegalArgumentException().isThrownBy(() -> service.isLocked("", DEPT, SESSION));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.isHeldByCurrentThread(TENANT, "", SESSION));
        assertThatIllegalArgumentException().isThrownBy(() -> service.forceUnlock(TENANT, DEPT, " "));
        verifyNoInteractions(redissonClient);
    }

    // ------------------------------------------------------------------ concurrency

    @Test
    @DisplayName("concurrent callers on one session are serialized, never overlapping")
    void concurrentCallersAreSerialized() throws Exception {
        RedissonClient client = mock(RedissonClient.class);
        RLock rLock = mock(RLock.class);
        ReentrantLock delegate = new ReentrantLock();
        when(client.getLock(KEY)).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), any(TimeUnit.class)))
                .thenAnswer(invocation -> delegate.tryLock(
                        invocation.getArgument(0), invocation.getArgument(1)));
        when(rLock.isHeldByCurrentThread()).thenAnswer(invocation -> delegate.isHeldByCurrentThread());
        org.mockito.Mockito.doAnswer(invocation -> {
            delegate.unlock();
            return null;
        }).when(rLock).unlock();

        MedLockProperties lockProperties = new MedLockProperties();
        lockProperties.setWaitTime(Duration.ofSeconds(5));
        SessionLockService concurrentService = new SessionLockService(client, lockProperties);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger inCriticalSection = new AtomicInteger();
        AtomicInteger maxObservedOverlap = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();
        int[] sharedCounter = new int[1];
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    start.await();
                    concurrentService.runLocked(TENANT, DEPT, SESSION, () -> {
                        int concurrent = inCriticalSection.incrementAndGet();
                        maxObservedOverlap.accumulateAndGet(concurrent, Math::max);
                        // deliberately non-atomic: only mutual exclusion can keep it consistent
                        sharedCounter[0] = sharedCounter[0] + 1;
                        inCriticalSection.decrementAndGet();
                    });
                    completed.incrementAndGet();
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(completed).hasValue(threads);
        assertThat(sharedCounter[0]).isEqualTo(threads);
        assertThat(maxObservedOverlap).hasValue(1);
        assertThat(delegate.isLocked()).isFalse();
    }
}
