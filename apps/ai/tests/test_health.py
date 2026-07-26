from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def test_health_returns_200_with_service_and_version():
    response = client.get("/health")
    assert response.status_code == 200
    body = response.json()
    assert body["service"] == "ai"
    assert body["status"] == "UP"
    assert "version" in body


def test_unknown_route_returns_404_json_no_traceback():
    response = client.get("/does-not-exist")
    assert response.status_code == 404
    body = response.json()
    assert "detail" in body
    assert "traceback" not in body
    assert "trace" not in body


def test_docs_disabled_by_default():
    assert client.get("/docs").status_code == 404
    assert client.get("/openapi.json").status_code == 404
