"""Which RateLimiter backend an unset/blank AI_RATE_LIMIT_REDIS_URL actually selects.

A production outage traced back here: config.py's rate_limit_redis_url default used to be
"redis://localhost:6379/0" -- a real-looking URL, not blank -- so leaving the env var unset (as
render.yaml deliberately does on the free tier, which has no managed Redis) still handed
select_rate_limiter a non-empty string and it silently chose RedisRateLimiter. Every request then
failed closed with 503 the moment that Redis connection was refused. docker-compose.yml sets the
env var explicitly for local dev, so this path was never exercised there -- only a fresh Settings()
import with nothing overriding it reproduces what Render actually saw.
"""

import subprocess
import sys
from pathlib import Path

from app.rate_limit import InMemoryRateLimiter, RedisRateLimiter, select_rate_limiter

AI_APP_ROOT = Path(__file__).resolve().parents[1]


def test_select_rate_limiter_with_blank_url_returns_in_memory():
    assert isinstance(select_rate_limiter(""), InMemoryRateLimiter)


def test_select_rate_limiter_with_a_real_url_returns_redis():
    assert isinstance(select_rate_limiter("redis://example:6379/0"), RedisRateLimiter)


def test_settings_default_rate_limit_redis_url_is_blank_without_the_env_var(monkeypatch):
    # A subprocess import, not just reading config.py's source: this is what actually
    # determines the backend at startup, and a fresh interpreter guarantees no other test's
    # module-level `settings` singleton (already imported once for this whole process via
    # conftest.py) or lingering env var masks what a real, first-ever import would see.
    import os

    env = dict(os.environ)
    env.pop("AI_RATE_LIMIT_REDIS_URL", None)
    env["AI_LLM_PROVIDER"] = "fake"
    env["AI_EMBEDDING_PROVIDER"] = "fake"
    env["AI_SERVICE_TOKEN"] = "test-token"

    result = subprocess.run(
        [sys.executable, "-c", "from app.config import settings; print(repr(settings.rate_limit_redis_url))"],
        cwd=AI_APP_ROOT,
        env=env,
        capture_output=True,
        text=True,
        timeout=30,
    )

    assert result.returncode == 0, result.stderr
    assert result.stdout.strip() == "''"
