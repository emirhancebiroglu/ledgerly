package com.ledgerly.api.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Covers the translation this adapter owns after M9.9 T1: a Redis-level failure becoming {@link
 * RateLimitUnavailableException}, which the callers above it treat as a rejection rather than
 * inspecting Redis exception types themselves.
 */
@ExtendWith(MockitoExtension.class)
class RedisRateLimiterTest {

  @Mock private StringRedisTemplate redisTemplate;

  @Test
  void a_connection_failure_becomes_an_unavailable_quota() {
    when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString()))
        .thenThrow(new RedisConnectionFailureException("redis unavailable"));

    assertThatThrownBy(() -> new RedisRateLimiter(redisTemplate).acquire("k", 2, 60))
        .isInstanceOf(RateLimitUnavailableException.class);
  }

  /**
   * A null result means the script did not report a quota at all. Returning it verbatim would read
   * as a negative-free admission upstream, so it must fail closed like any other outage.
   */
  @Test
  void a_missing_script_result_becomes_an_unavailable_quota() {
    when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString()))
        .thenReturn(null);

    assertThatThrownBy(() -> new RedisRateLimiter(redisTemplate).acquire("k", 2, 60))
        .isInstanceOf(RateLimitUnavailableException.class);
  }

  @Test
  void the_scripts_ttl_is_returned_verbatim_so_the_caller_owns_the_verdict() {
    when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString()))
        .thenReturn(-17L);

    assertThat(new RedisRateLimiter(redisTemplate).acquire("k", 2, 60)).isEqualTo(-17L);
  }
}
