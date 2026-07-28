"""JSON logging with a request-scoped correlation id and no request-body serialization."""

import json
import logging
import re
from contextvars import ContextVar
from datetime import UTC, datetime
from uuid import UUID, uuid4

_correlation_id: ContextVar[str | None] = ContextVar("correlation_id", default=None)
_BEARER_TOKEN = re.compile(r"(?i)bearer\s+[^\s,;]+")
_EMAIL = re.compile(r"(?i)\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b")
_SENSITIVE_FIELD = re.compile(
    r"(?i)\b(llm_?key|api_?key|token|authorization|filename|proposal|document_?bytes|content)\s*=\s*[^\s,;]+"
)
_PROVIDER_KEY = re.compile(r"\b(?:sk-[A-Za-z0-9_-]+|AIza[A-Za-z0-9_-]+)\b")


class JsonFormatter(logging.Formatter):
    """Emit only safe, parseable operational fields on one line."""

    def format(self, record: logging.LogRecord) -> str:
        payload: dict[str, str | int] = {
            "@timestamp": datetime.now(UTC).isoformat(),
            "@version": "1",
            "level": record.levelname,
            "level_value": record.levelno,
            "service": "ai",
            "logger_name": record.name,
            "thread_name": record.threadName,
            "message": _redact(record.getMessage()),
        }
        correlation_id = _correlation_id.get()
        if correlation_id is not None:
            payload["correlationId"] = correlation_id
        if record.exc_info is not None:
            payload["exceptionType"] = record.exc_info[0].__name__
        return json.dumps(payload, separators=(",", ":"), ensure_ascii=False)


def configure_logging() -> None:
    handler = logging.StreamHandler()
    handler.setFormatter(JsonFormatter())
    root = logging.getLogger()
    root.handlers.clear()
    root.addHandler(handler)
    root.setLevel(logging.INFO)
    # Uvicorn configures dedicated access/error handlers after importing the application unless
    # they are made to use this formatter explicitly; one plaintext access line breaks ingestion.
    for logger_name in ("uvicorn", "uvicorn.error", "uvicorn.access"):
        logger = logging.getLogger(logger_name)
        logger.handlers.clear()
        logger.addHandler(handler)
        logger.propagate = False


def set_correlation_id(value: str | None) -> object:
    return _correlation_id.set(_safe_correlation_id(value))


def reset_correlation_id(token: object) -> None:
    _correlation_id.reset(token)


def _redact(message: str) -> str:
    """A last line of defense; request bodies are never intentionally logged."""
    redacted = _BEARER_TOKEN.sub("Bearer [REDACTED]", message)
    redacted = _EMAIL.sub("[REDACTED_EMAIL]", redacted)
    redacted = _SENSITIVE_FIELD.sub(lambda match: f"{match.group(1)}=[REDACTED]", redacted)
    return _PROVIDER_KEY.sub("[REDACTED]", redacted)


def _safe_correlation_id(value: str | None) -> str:
    try:
        return str(UUID(value))
    except (ValueError, TypeError, AttributeError):
        return str(uuid4())
