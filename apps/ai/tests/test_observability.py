import json
import logging

from app.observability import JsonFormatter, reset_correlation_id, set_correlation_id


def test_json_formatter_is_parseable_correlated_and_redacts_common_secrets():
    token = set_correlation_id("3d3811bc-6353-4d0b-864a-7ed86ae97ece")
    try:
        record = logging.LogRecord(
            "ai.boundary",
            logging.ERROR,
            __file__,
            1,
            "request failed email=alice@example.test authorization=Bearer service-token-123 "
            "llm_key=sk-secret-llm-key api_key=AIzaSecret filename=private-invoice.pdf "
            "proposal={private-content}",
            (),
            None,
        )
        event = json.loads(JsonFormatter().format(record))
    finally:
        reset_correlation_id(token)

    assert "@timestamp" in event
    assert event["service"] == "ai"
    assert event["level"] == "ERROR"
    assert event["logger_name"] == "ai.boundary"
    assert event["correlationId"] == "3d3811bc-6353-4d0b-864a-7ed86ae97ece"
    assert "alice@example.test" not in event["message"]
    assert "service-token-123" not in event["message"]
    assert "sk-secret-llm-key" not in event["message"]
    assert "AIzaSecret" not in event["message"]
    assert "private-invoice.pdf" not in event["message"]
    assert "private-content" not in event["message"]


def test_unsafe_correlation_id_is_not_emitted_verbatim():
    token = set_correlation_id("Bearer service-token-123")
    try:
        event = json.loads(JsonFormatter().format(logging.LogRecord("ai", logging.INFO, __file__, 1, "ok", (), None)))
    finally:
        reset_correlation_id(token)

    assert event["correlationId"] != "Bearer service-token-123"
