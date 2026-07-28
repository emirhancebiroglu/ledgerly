"""Unit tests never need a real provider or network — force the stub before ``app.config``
(and anything importing it) is loaded for the first time in this process.
"""

import os

os.environ.setdefault("AI_LLM_PROVIDER", "fake")
os.environ.setdefault("AI_EMBEDDING_PROVIDER", "fake")
os.environ.setdefault("AI_SERVICE_TOKEN", "test-service-token")
os.environ.setdefault("AI_RATE_LIMIT_ENABLED", "false")


import pytest


@pytest.fixture(autouse=True)
def authenticate_module_client(request):
    """Existing endpoint tests exercise their contract as api's authenticated caller."""
    client = getattr(request.module, "client", None)
    if client is not None:
        client.headers["Authorization"] = "Bearer test-service-token"
