"""The anomaly graph: explain deterministic facts, then stop.

Risk classification and all numbers are deliberately outside this graph. The graph cannot change
them because its only output is an explanation string.
"""

from __future__ import annotations

import json
import re
from typing import TypedDict

from langgraph.graph import END, StateGraph

from app.llm.client import LlmClient, LlmError

_MARKDOWN_JSON_FENCE = re.compile(r"^```(?:json)?\s*(.*?)\s*```$", re.DOTALL)


class AnomalyFailedError(RuntimeError):
    """The graph could not produce a parseable qualitative explanation."""


class AnomalyState(TypedDict):
    instruction: str
    explanation: str | None


def _strip_markdown_fence(raw: str) -> str:
    match = _MARKDOWN_JSON_FENCE.match(raw.strip())
    return match.group(1) if match else raw


def build_anomaly_graph(llm_client: LlmClient):
    def explain(state: AnomalyState) -> AnomalyState:
        try:
            raw = llm_client.complete(state["instruction"])
        except LlmError as error:
            raise AnomalyFailedError(str(error)) from error
        try:
            parsed = json.loads(_strip_markdown_fence(raw))
        except json.JSONDecodeError as error:
            raise AnomalyFailedError("Model returned output that is not valid JSON") from error
        explanation = parsed.get("explanation") if isinstance(parsed, dict) else None
        if not isinstance(explanation, str) or not explanation.strip():
            raise AnomalyFailedError("Model did not return a qualitative explanation")
        return {**state, "explanation": explanation.strip()}

    graph = StateGraph(AnomalyState)
    graph.add_node("explain", explain)
    graph.set_entry_point("explain")
    graph.add_edge("explain", END)
    return graph.compile()


def run_anomaly_graph(llm_client: LlmClient, instruction: str) -> str:
    """Run the explanation-only graph."""
    graph = build_anomaly_graph(llm_client)
    final_state = graph.invoke({"instruction": instruction, "explanation": None})
    return final_state["explanation"]
