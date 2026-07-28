package com.ledgerly.api.ratelimit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

@ExtendWith(MockitoExtension.class)
class UploadRateLimiterTest {

  @Mock private StringRedisTemplate redisTemplate;

  @Test
  void redis_failure_rejects_cost_bearing_uploads() {
    when(
            redisTemplate.execute(
                any(DefaultRedisScript.class), anyList(), anyString(), anyString()))
        .thenThrow(new RedisConnectionFailureException("redis unavailable"));
    UploadRateLimiter limiter = new UploadRateLimiter(redisTemplate, 2, 60, 1, 60);

    assertThatThrownBy(() -> limiter.checkDocumentUpload(UUID.randomUUID()))
        .isInstanceOf(RateLimitUnavailableException.class);
  }
}
