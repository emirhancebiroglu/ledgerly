"""The vision -> structured-output extraction graph.

Two nodes: ``extract`` calls the model once against the document; ``self_check`` runs at most
once more, only when a gated field's confidence is below threshold, to give the model one chance
to correct itself against its own prior answer. The loop bound is a state counter checked in the
routing function, not an unconditional edge back into ``self_check`` — a graph cannot re-enter
that node twice no matter what the model returns.

``ExtractionService`` still does the final schema validation after the graph returns; this module
only owns getting from bytes to a JSON-shaped dict with confidence.
"""

from __future__ import annotations

import json
import logging
import re
from typing import TypedDict

from langgraph.graph import END, StateGraph

from app.llm.client import LlmClient, LlmError, VisionPrompt

logger = logging.getLogger(__name__)

# Fields the M5 gate measures (total, date, currency) plus tax, which the schema also requires a
# confidence for. Below this, the model gets one chance to look again.
GATED_FIELDS = ("currency", "total_minor", "tax_minor", "document_date")
CONFIDENCE_THRESHOLD = 0.7

SELF_CHECK_INSTRUCTION_TEMPLATE = (
    "You previously extracted the following from this document, but your own confidence on "
    "{fields} was below {threshold}:\n{previous}\n\n"
    "Look at the document again and return a corrected JSON object in the same schema. If your "
    "original answer was already correct, return it unchanged, but raise the confidence for the "
    "field(s) you re-checked."
)


class ExtractionFailedError(RuntimeError):
    """The graph could not produce a parseable proposal."""


class GraphState(TypedDict):
    content: bytes
    content_type: str
    instruction: str
    extracted: dict | None
    self_check_ran: bool
    self_checked_fields: list[str]


_MARKDOWN_JSON_FENCE = re.compile(r"^```(?:json)?\s*(.*?)\s*```$", re.DOTALL)


def _strip_markdown_fence(raw: str) -> str:
    """Models are told not to wrap JSON in a code fence but sometimes do anyway; strip one if
    present rather than failing the whole extraction over formatting."""
    match = _MARKDOWN_JSON_FENCE.match(raw.strip())
    return match.group(1) if match else raw


def _low_confidence_fields(extracted: dict) -> list[str]:
    confidence = extracted.get("confidence", {})
    return [
        field
        for field in GATED_FIELDS
        if confidence.get(field, 0.0) < CONFIDENCE_THRESHOLD
    ]


def build_extraction_graph(llm_client: LlmClient):
    def extract(state: GraphState) -> GraphState:
        try:
            raw = llm_client.complete_vision(
                VisionPrompt(
                    instruction=state["instruction"],
                    content=state["content"],
                    content_type=state["content_type"],
                )
            )
        except LlmError as error:
            raise ExtractionFailedError(str(error)) from error

        try:
            extracted = json.loads(_strip_markdown_fence(raw))
        except json.JSONDecodeError as error:
            raise ExtractionFailedError("Model returned output that is not valid JSON") from error

        return {**state, "extracted": extracted}

    def self_check(state: GraphState) -> GraphState:
        extracted = state["extracted"]
        low_confidence = _low_confidence_fields(extracted)

        instruction = SELF_CHECK_INSTRUCTION_TEMPLATE.format(
            fields=", ".join(low_confidence),
            threshold=CONFIDENCE_THRESHOLD,
            previous=json.dumps(extracted),
        )

        try:
            raw = llm_client.complete_vision(
                VisionPrompt(
                    instruction=instruction,
                    content=state["content"],
                    content_type=state["content_type"],
                )
            )
        except LlmError as error:
            # The self-check is a best-effort improvement, not a required step: if it fails, the
            # original (already schema-checkable) extraction still stands.
            logger.info(
                "Self-check call failed, keeping the original extraction exceptionType=%s",
                type(error).__name__,
            )
            return {**state, "self_check_ran": True, "self_checked_fields": low_confidence}

        try:
            corrected = json.loads(_strip_markdown_fence(raw))
        except json.JSONDecodeError:
            logger.info("Self-check returned non-JSON, keeping the original extraction")
            return {**state, "self_check_ran": True, "self_checked_fields": low_confidence}

        return {
            **state,
            "extracted": corrected,
            "self_check_ran": True,
            "self_checked_fields": low_confidence,
        }

    def route_after_extract(state: GraphState) -> str:
        # The bound that makes this loop-proof: self_check runs only from this one edge, and
        # nothing routes back into extract or self_check afterwards.
        if state["self_check_ran"]:
            return END
        if _low_confidence_fields(state["extracted"]):
            return "self_check"
        return END

    graph = StateGraph(GraphState)
    graph.add_node("extract", extract)
    graph.add_node("self_check", self_check)
    graph.set_entry_point("extract")
    graph.add_conditional_edges("extract", route_after_extract, {"self_check": "self_check", END: END})
    graph.add_edge("self_check", END)

    return graph.compile()


class ExtractionResult(TypedDict):
    extracted: dict
    self_checked_fields: list[str]


def run_extraction_graph(
    llm_client: LlmClient, instruction: str, content: bytes, content_type: str
) -> ExtractionResult:
    """Runs the graph and returns the final extracted dict plus self-check bookkeeping.

    ``self_checked_fields`` is kept separate from ``extracted`` deliberately — the schema the
    extracted dict must satisfy is closed (``additionalProperties: false``), so bookkeeping about
    *how* the proposal was produced cannot ride inside it.

    :raises ExtractionFailedError: if the model fails or its output is not valid JSON.
    """
    graph = build_extraction_graph(llm_client)
    initial_state: GraphState = {
        "content": content,
        "content_type": content_type,
        "instruction": instruction,
        "extracted": None,
        "self_check_ran": False,
        "self_checked_fields": [],
    }
    final_state = graph.invoke(initial_state)
    return {
        "extracted": final_state["extracted"],
        "self_checked_fields": final_state["self_checked_fields"],
    }
