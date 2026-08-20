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
from app.policy.text_extraction import UnreadablePdfError, extract_pdf_text

logger = logging.getLogger(__name__)

# The corpus's primary miss cluster is vendor identity. It is re-read once even when the model is
# confident: issuer-vs-recipient confusion is frequently overconfident. The remaining fields are
# selected by confidence and all re-checks still share the graph's one-pass bound.
GATED_FIELDS = ("vendor", "currency", "total_minor", "tax_minor", "document_date")
CONFIDENCE_THRESHOLD = 0.7

SELF_CHECK_INSTRUCTION_TEMPLATE = (
    "Read this invoice and identify BOTH the seller who issued it and the buyer/customer internally. "
    "Return ONLY one JSON object containing exactly these field(s): {fields}. Do not return any "
    "other fields or an explanation. A field may be null only when unreadable or absent. For vendor, "
    "return the complete legal seller/issuer exactly as printed; never return the buyer, recipient, "
    "bill-to party, marketplace, or payment provider. Do not rely on a prior extraction. Use the "
    "source text below as an additional reading aid when present; it is not a substitute for checking "
    "the rendered document.\n\nINVOICE TEXT:\n{text_hint}"
)

VENDOR_SELF_CHECK_INSTRUCTION_TEMPLATE = (
    "Read the invoice text below. Identify BOTH the seller who issued the invoice and the "
    "buyer/customer internally. Return ONLY JSON with one key: vendor. vendor must be the complete "
    "legal seller/issuer exactly as printed; it must never be the buyer, recipient, bill-to party, "
    "marketplace, or payment provider.\n\nINVOICE TEXT:\n{text_hint}"
)

_TEXT_HINT_LIMIT = 12_000


class ExtractionFailedError(RuntimeError):
    """The graph could not produce a parseable proposal."""


class GraphState(TypedDict):
    content: bytes
    content_type: str
    instruction: str
    extracted: dict | None
    original_extracted: dict | None
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


def _fields_to_self_check(extracted: dict) -> list[str]:
    """Returns the bounded re-check set, prioritizing the common issuer-identity error."""
    fields = _low_confidence_fields(extracted)
    vendor = extracted.get("vendor")
    if isinstance(vendor, str) and vendor.strip() and "vendor" not in fields:
        return ["vendor", *fields]
    return fields


def _merge_checked_fields(original: dict, corrected: dict, fields_to_check: list[str]) -> dict:
    """Accept only the fields explicitly re-checked; unrelated facts stay from the first pass."""
    merged = dict(original)
    for field in fields_to_check:
        if field in corrected:
            merged[field] = corrected[field]

    original_confidence = original.get("confidence")
    corrected_confidence = corrected.get("confidence")
    if isinstance(original_confidence, dict) and isinstance(corrected_confidence, dict):
        merged_confidence = dict(original_confidence)
        for field in fields_to_check:
            if field in corrected_confidence:
                merged_confidence[field] = corrected_confidence[field]
        merged["confidence"] = merged_confidence
    return merged


def _text_hint(content: bytes, content_type: str) -> str | None:
    if content_type != "application/pdf":
        return None
    try:
        text = extract_pdf_text(content)
    except UnreadablePdfError:
        return None
    normalized = text.strip()
    if not normalized:
        return None
    return normalized[:_TEXT_HINT_LIMIT]


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

        return {**state, "extracted": extracted, "original_extracted": extracted}

    def self_check(state: GraphState) -> GraphState:
        extracted = state["extracted"]
        fields_to_check = _fields_to_self_check(extracted)

        text_hint = _text_hint(state["content"], state["content_type"])
        prompt_text = text_hint or "(No embedded text is available; inspect the rendered document.)"
        instruction = (
            VENDOR_SELF_CHECK_INSTRUCTION_TEMPLATE.format(text_hint=prompt_text)
            if fields_to_check == ["vendor"]
            else SELF_CHECK_INSTRUCTION_TEMPLATE.format(
                fields=", ".join(fields_to_check), text_hint=prompt_text
            )
        )

        try:
            # Text PDFs retain labels and reading order that vision models can blur across dense
            # invoice layouts. Use that precise representation for the focused re-check; scans
            # and images still take the existing rendered-vision path.
            raw = (
                llm_client.complete(instruction)
                if text_hint is not None
                else llm_client.complete_vision(
                    VisionPrompt(
                        instruction=instruction,
                        content=state["content"],
                        content_type=state["content_type"],
                    )
                )
            )
        except LlmError as error:
            # The self-check is a best-effort improvement, not a required step: if it fails, the
            # original (already schema-checkable) extraction still stands.
            logger.info(
                "Self-check call failed, keeping the original extraction exceptionType=%s",
                type(error).__name__,
            )
            return {**state, "self_check_ran": True, "self_checked_fields": fields_to_check}

        try:
            corrected = json.loads(_strip_markdown_fence(raw))
        except json.JSONDecodeError:
            logger.info("Self-check returned non-JSON, keeping the original extraction")
            return {**state, "self_check_ran": True, "self_checked_fields": fields_to_check}

        return {
            **state,
            "extracted": _merge_checked_fields(extracted, corrected, fields_to_check),
            "self_check_ran": True,
            "self_checked_fields": fields_to_check,
        }

    def route_after_extract(state: GraphState) -> str:
        # The bound that makes this loop-proof: self_check runs only from this one edge, and
        # nothing routes back into extract or self_check afterwards.
        if state["self_check_ran"]:
            return END
        if _fields_to_self_check(state["extracted"]):
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
    original_extracted: dict
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
        "original_extracted": None,
        "self_check_ran": False,
        "self_checked_fields": [],
    }
    final_state = graph.invoke(initial_state)
    return {
        "extracted": final_state["extracted"],
        "original_extracted": final_state["original_extracted"],
        "self_checked_fields": final_state["self_checked_fields"],
    }
