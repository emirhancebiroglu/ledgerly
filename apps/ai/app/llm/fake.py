"""A deterministic stand-in for a real model.

This is not a mock in the test-double sense — it is the M4 implementation of the ``LlmClient``
port, exercised by the real ``POST /extract`` route. The point of M4 is to prove the contract and
the validation gate before any real model exists, so the far side has to be genuinely swappable.

Output is derived from the document bytes so identical input yields identical output: a stub that
returned random values would make the api-side tests flaky for reasons that have nothing to do with
the code under test.
"""

from __future__ import annotations

import hashlib
import json
from datetime import date, timedelta

from app.llm.client import LlmClient, LlmError, VisionPrompt

# A document this small cannot be a real invoice, image or PDF. Anything at or below this is
# treated as an unreadable upload rather than being given a confidently-wrong extraction.
MIN_READABLE_BYTES = 32


class FakeLlmClient(LlmClient):
    """Deterministic pseudo-extraction, keyed off a hash of the document bytes."""

    MODEL_NAME = "fake-llm-v1"

    @property
    def model_name(self) -> str:
        return self.MODEL_NAME

    def complete(self, prompt: str) -> str:
        return f"fake completion for: {prompt[:64]}"

    def complete_vision(self, prompt: VisionPrompt) -> str:
        if len(prompt.content) < MIN_READABLE_BYTES:
            raise LlmError("Document too small to contain readable content")

        digest = hashlib.sha256(prompt.content).digest()

        # Derive amounts that satisfy total == sum(lines) + tax exactly. The stub deliberately
        # produces arithmetically consistent output: it is the api-side validator's job to catch
        # inconsistency, and a stub that could never be consistent would never exercise the pass.
        line_one = 1000 + (int.from_bytes(digest[0:2], "big") % 90_000)
        line_two = 1000 + (int.from_bytes(digest[2:4], "big") % 90_000)
        subtotal = line_one + line_two
        tax = subtotal * 21 // 100
        total = subtotal + tax

        days_ago = int.from_bytes(digest[4:6], "big") % 365
        document_date = date.today() - timedelta(days=days_ago)

        vendor_suffix = digest[6:9].hex().upper()

        return json.dumps(
            {
                "vendor": f"Fake Vendor {vendor_suffix}",
                "currency": "EUR",
                "total_minor": total,
                "tax_minor": tax,
                "document_date": document_date.isoformat(),
                "lines": [
                    {
                        "description": "Fake line item A",
                        "quantity": 1000,
                        "amount_minor": line_one,
                    },
                    {
                        "description": "Fake line item B",
                        "quantity": 1000,
                        "amount_minor": line_two,
                    },
                ],
                "confidence": {
                    "vendor": 0.80,
                    "currency": 0.99,
                    "total_minor": 0.95,
                    "tax_minor": 0.90,
                    "document_date": 0.93,
                },
                "warnings": ["Extracted by the M4 stub; no real model was called"],
            }
        )
