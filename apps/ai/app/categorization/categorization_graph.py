"""The categorization graph: classify -> done.

A single node, unlike extraction's two-node self-check loop: retrieval already happened in `api`
(pgvector search against its own Postgres), so this graph's only job is picking one of the
organization's category names given the extracted fields and the retrieved policy chunk texts —
there is nothing here to self-check against a second look at a document, since no document bytes
ever reach this graph.
"""

from __future__ import annotations

import json
import re
from typing import TypedDict

from langgraph.graph import END, StateGraph

from app.llm.client import LlmClient, LlmError

_MARKDOWN_JSON_FENCE = re.compile(r"^```(?:json)?\s*(.*?)\s*```$", re.DOTALL)


class CategorizationFailedError(RuntimeError):
    """The graph could not produce a parseable classification."""


class CategorizationState(TypedDict):
    instruction: str
    classified: dict | None


def _strip_markdown_fence(raw: str) -> str:
    match = _MARKDOWN_JSON_FENCE.match(raw.strip())
    return match.group(1) if match else raw


def build_categorization_graph(llm_client: LlmClient):
    def classify(state: CategorizationState) -> CategorizationState:
        try:
            raw = llm_client.complete(state["instruction"])
        except LlmError as error:
            raise CategorizationFailedError(str(error)) from error

        try:
            classified = json.loads(_strip_markdown_fence(raw))
        except json.JSONDecodeError as error:
            raise CategorizationFailedError(
                "Model returned output that is not valid JSON"
            ) from error

        return {**state, "classified": classified}

    graph = StateGraph(CategorizationState)
    graph.add_node("classify", classify)
    graph.set_entry_point("classify")
    graph.add_edge("classify", END)

    return graph.compile()


def run_categorization_graph(llm_client: LlmClient, instruction: str) -> dict:
    """Runs the graph and returns the classified dict.

    :raises CategorizationFailedError: if the model fails or its output is not valid JSON.
    """
    graph = build_categorization_graph(llm_client)
    final_state = graph.invoke({"instruction": instruction, "classified": None})
    return final_state["classified"]
