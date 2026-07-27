"""Stable, code-defined markers in the categorization prompt.

Split into their own module with no other dependencies so both
``app.categorization.categorization`` (which writes them into the prompt) and
``app.llm.fake`` (which parses them back out for a deterministic stub response) can import them
without creating an import cycle between ``app.categorization`` and ``app.llm``.
"""

CATEGORIES_MARKER = "ALLOWED_CATEGORIES:"
POLICY_CHUNKS_MARKER = "POLICY_CHUNKS:"
