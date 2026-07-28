"""Deterministic anomaly assessment followed by an explanation-only LLM graph."""

from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal, InvalidOperation, localcontext
import math

from jsonschema import Draft202012Validator

from app.anomaly.anomaly_graph import AnomalyFailedError as GraphAnomalyFailedError
from app.anomaly.anomaly_graph import run_anomaly_graph
from app.anomaly.prompt_markers import ANOMALY_FACTS_MARKER
from app.config import settings
from app.contracts import ANOMALY_RESPONSE_SCHEMA, load_schema
from app.llm.client import LlmClient

_SAFE_FALLBACK_EXPLANATION = "The deterministic assessment is available in the structured fields."

ANOMALY_INSTRUCTION_TEMPLATE = """\\
Explain the deterministic anomaly assessment below in one concise, qualitative sentence. Return
ONLY a JSON object with exactly this shape:

{{"explanation": "<qualitative explanation without digits or numerical claims>"}}

Do not repeat, alter, estimate, or invent any number. The structured fields are produced by code
and are the only source of numerical facts.

{facts_marker}
{facts}
"""


class AnomalyFailedError(RuntimeError):
    """The anomaly service could not form a schema-valid advisory response."""


@dataclass(frozen=True)
class AnomalyStatistics:
    history_count: int
    z_score: float
    budget_burn_rate: float | None
    risk: str


def calculate_statistics(
    amount_minor: int, history_amounts_minor: list[int], budget: dict | None
) -> AnomalyStatistics:
    """Classify risk from finite Decimal arithmetic before an LLM is called."""
    with localcontext() as context:
        context.prec = 40
        candidate = Decimal(amount_minor)
        values = [Decimal(value) for value in history_amounts_minor]
        z_score = Decimal(0)
        if len(values) >= 2:
            mean = sum(values) / Decimal(len(values))
            variance = sum((value - mean) ** 2 for value in values) / Decimal(len(values))
            if variance != 0:
                z_score = (candidate - mean) / variance.sqrt()

        burn_rate = None
        if budget is not None:
            burn_rate = Decimal(budget["spent_minor"]) / Decimal(budget["limit_minor"])

    z_score_float = _finite_float(z_score)
    burn_rate_float = _finite_float(burn_rate) if burn_rate is not None else None
    absolute_z = abs(z_score_float)
    if (
        absolute_z >= settings.anomaly_high_z_score
        or (burn_rate_float is not None and burn_rate_float >= settings.anomaly_high_burn_rate)
    ):
        risk = "HIGH"
    elif (
        absolute_z >= settings.anomaly_medium_z_score
        or (burn_rate_float is not None and burn_rate_float >= settings.anomaly_medium_burn_rate)
    ):
        risk = "MEDIUM"
    else:
        risk = "LOW"
    return AnomalyStatistics(len(values), z_score_float, burn_rate_float, risk)


def _finite_float(value: Decimal) -> float:
    try:
        result = float(value)
    except (OverflowError, ValueError, InvalidOperation) as error:
        raise AnomalyFailedError("Anomaly calculation was not finite") from error
    if not math.isfinite(result):
        raise AnomalyFailedError("Anomaly calculation was not finite")
    return result


class AnomalyService:
    """Coordinates deterministic scoring and a strictly qualitative LLM completion."""

    def __init__(self, llm_client: LlmClient) -> None:
        self._llm_client = llm_client
        self._validator = Draft202012Validator(load_schema(ANOMALY_RESPONSE_SCHEMA))

    def analyze(
        self,
        expense_id: str,
        amount_minor: int,
        history: list[dict],
        budget: dict | None,
    ) -> dict:
        statistics = calculate_statistics(
            amount_minor, [entry["amount_minor"] for entry in history], budget
        )
        facts = {
            "risk": statistics.risk,
            "history_count": statistics.history_count,
            "z_score": statistics.z_score,
            "budget_burn_rate": statistics.budget_burn_rate,
        }
        instruction = ANOMALY_INSTRUCTION_TEMPLATE.format(
            facts_marker=ANOMALY_FACTS_MARKER,
            facts=facts,
        )
        try:
            explanation = run_anomaly_graph(self._llm_client, instruction)
        except GraphAnomalyFailedError as error:
            raise AnomalyFailedError(str(error)) from error

        # Prose must not become a shadow numeric channel. If the model ignores the instruction,
        # preserve the deterministic assessment but discard the unsafe completion.
        if any(character.isdigit() for character in explanation):
            explanation = _SAFE_FALLBACK_EXPLANATION
        response = {
            "expense_id": expense_id,
            **facts,
            "explanation": explanation,
            "model": self._llm_client.model_name,
        }
        errors = sorted(self._validator.iter_errors(response), key=lambda error: error.path)
        if errors:
            raise AnomalyFailedError("Anomaly response did not satisfy the shared schema")
        return response
