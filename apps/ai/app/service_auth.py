"""Authentication for requests that can invoke an LLM or embedding provider."""

import hmac

from fastapi import Request
from fastapi.responses import JSONResponse
from pydantic import SecretStr

_PROTECTED_PATHS = frozenset(
    {"/extract", "/categorize", "/embed-policy", "/embed-query", "/anomaly"}
)
_UNAUTHORIZED = JSONResponse(
    status_code=401,
    content={"detail": "Unauthorized"},
    headers={"WWW-Authenticate": "Bearer"},
)


def is_cost_bearing_agent_request(request: Request) -> bool:
    return request.method == "POST" and request.url.path in _PROTECTED_PATHS


async def require_service_auth(request: Request, service_token: SecretStr) -> JSONResponse | None:
    """Reject unauthenticated agent calls before FastAPI reads a request body."""
    if not is_cost_bearing_agent_request(request):
        return None

    authorization = request.headers.get("Authorization", "")
    expected = f"Bearer {service_token.get_secret_value()}"
    if not hmac.compare_digest(authorization, expected):
        return _UNAUTHORIZED
    return None
