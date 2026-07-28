from fastapi.testclient import TestClient

from app.main import app, settings
from app.rate_limit import RateLimitExceeded, RateLimitUnavailable


class RejectingLimiter:
    async def check(self, path: str) -> None:
        raise RateLimitExceeded(17)


class UnavailableLimiter:
    async def check(self, path: str) -> None:
        raise RateLimitUnavailable


class CountingLimiter:
    def __init__(self) -> None:
        self.calls: list[str] = []

    async def check(self, path: str) -> None:
        self.calls.append(path)


def test_authenticated_agent_call_returns_429_with_retry_after_when_quota_is_exhausted(monkeypatch):
    monkeypatch.setattr(settings, "rate_limit_enabled", True)
    monkeypatch.setattr(app.state, "rate_limiter", RejectingLimiter())
    client = TestClient(app)

    response = client.post(
        "/embed-query",
        headers={"Authorization": "Bearer test-service-token"},
        json={"text": "ignored-before-validation"},
    )

    assert response.status_code == 429
    assert response.headers["Retry-After"] == "17"
    assert response.json() == {"detail": "Rate limit exceeded"}


def test_rate_limit_redis_failure_fails_closed_for_agent_calls(monkeypatch):
    monkeypatch.setattr(settings, "rate_limit_enabled", True)
    monkeypatch.setattr(app.state, "rate_limiter", UnavailableLimiter())
    client = TestClient(app)

    response = client.post(
        "/embed-query",
        headers={"Authorization": "Bearer test-service-token"},
        json={"text": "valid input"},
    )

    assert response.status_code == 503
    assert response.json() == {"detail": "Rate limiting is temporarily unavailable"}


def test_malformed_agent_body_does_not_consume_quota(monkeypatch):
    limiter = CountingLimiter()
    monkeypatch.setattr(settings, "rate_limit_enabled", True)
    monkeypatch.setattr(app.state, "rate_limiter", limiter)
    client = TestClient(app)

    malformed = client.post(
        "/embed-query", headers={"Authorization": "Bearer test-service-token"}, json={}
    )
    valid = client.post(
        "/embed-query",
        headers={"Authorization": "Bearer test-service-token"},
        json={"text": "valid input"},
    )

    assert malformed.status_code == 422
    assert valid.status_code == 200
    assert limiter.calls == ["/embed-query"]
