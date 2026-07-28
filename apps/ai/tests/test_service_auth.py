from fastapi.testclient import TestClient

from app.main import app


def test_health_is_public_but_every_cost_bearing_route_requires_service_authentication():
    client = TestClient(app)

    assert client.get("/health").status_code == 200

    for path in ("/extract", "/categorize", "/embed-policy", "/embed-query", "/anomaly"):
        response = client.post(path)
        assert response.status_code == 401
        assert response.json() == {"detail": "Unauthorized"}
        assert response.headers["WWW-Authenticate"] == "Bearer"


def test_wrong_service_credential_is_rejected_before_request_validation():
    client = TestClient(app)

    response = client.post("/extract", headers={"Authorization": "Bearer wrong-token"})

    assert response.status_code == 401
    assert response.json() == {"detail": "Unauthorized"}
