package com.med.qa.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import com.med.qa.config.MedRateLimitProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;

import java.util.concurrent.TimeUnit;

/**
 * Unit tests of {@link RateLimitService} over a mocked {@link RedissonClient} / {@link RRateLimiter},
 * so no Redis is contacted.
 */
class RateLimitServiceTest {

    private static final String KEY = "med:ratelimit:patient:p1:TestTarget#rated";

    private final RedissonClient client = mock(RedissonClient.class);

    private final RRateLimiter limiter = mock(RRateLimiter.class);

    private RateLimitService service(long timeoutMillis) {
        MedRateLimitProperties props = new MedRateLimitProperties();
        props.setAcquireTimeoutMillis(timeoutMillis);
        when(client.getRateLimiter(any(String.class))).thenReturn(limiter);
        return new RateLimitService(client, props);
    }

    @Test
    @DisplayName("acquires a permit when the bucket has capacity")
    void acquiresWhenAvailable() {
        when(limiter.trySetRate(any(RateType.class), any(Long.class), any(Long.class), any(RateIntervalUnit.class)))
                .thenReturn(true);
        when(limiter.tryAcquire(eq(1L), eq(0L), eq(TimeUnit.MILLISECONDS))).thenReturn(true);

        assertThat(service(0L).tryAcquire(KEY, 5, 1)).isTrue();
        verify(limiter).trySetRate(RateType.OVERALL, 5L, 1L, RateIntervalUnit.SECONDS);
        verify(limiter).tryAcquire(1L, 0L, TimeUnit.MILLISECONDS);
    }

    @Test
    @DisplayName("waits up to the configured timeout when acquiring")
    void honoursAcquireTimeout() {
        when(limiter.trySetRate(any(RateType.class), any(Long.class), any(Long.class), any(RateIntervalUnit.class)))
                .thenReturn(true);
        when(limiter.tryAcquire(eq(1L), eq(200L), eq(TimeUnit.MILLISECONDS))).thenReturn(true);

        assertThat(service(200L).tryAcquire(KEY, 5, 1)).isTrue();
        verify(limiter).tryAcquire(1L, 200L, TimeUnit.MILLISECONDS);
    }

    @Test
    @DisplayName("reports exhaustion without throwing")
    void reportsExhaustion() {
        when(limiter.trySetRate(any(RateType.class), any(Long.class), any(Long.class), any(RateIntervalUnit.class)))
                .thenReturn(true);
        when(limiter.tryAcquire(any(Long.class), any(Long.class), any(TimeUnit.class))).thenReturn(false);

        assertThat(service(0L).tryAcquire(KEY, 5, 1)).isFalse();
    }

    @Test
    @DisplayName("fails closed on a Redis error during acquisition")
    void redisErrorOnAcquire() {
        when(limiter.trySetRate(any(RateType.class), any(Long.class), any(Long.class), any(RateIntervalUnit.class)))
                .thenReturn(true);
        when(limiter.tryAcquire(any(Long.class), any(Long.class), any(TimeUnit.class)))
                .thenThrow(new RedisException("redis down"));

        BizException ex = assertThrows(BizException.class, () -> service(0L).tryAcquire(KEY, 5, 1));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.STORAGE_ERROR);
    }

    @Test
    @DisplayName("fails closed on a Redis error during rate seeding")
    void redisErrorOnSeed() {
        when(limiter.trySetRate(any(RateType.class), any(Long.class), any(Long.class), any(RateIntervalUnit.class)))
                .thenThrow(new RedisException("redis down"));

        BizException ex = assertThrows(BizException.class, () -> service(0L).tryAcquire(KEY, 5, 1));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.STORAGE_ERROR);
    }

    @Test
    @DisplayName("rejects a blank key")
    void blankKey() {
        assertThatThrownBy(() -> service(0L).tryAcquire("  ", 5, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rejects a non-positive rate")
    void nonPositiveRate() {
        assertThatThrownBy(() -> service(0L).tryAcquire(KEY, 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rejects a non-positive duration")
    void nonPositiveDuration() {
        assertThatThrownBy(() -> service(0L).tryAcquire(KEY, 5, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
