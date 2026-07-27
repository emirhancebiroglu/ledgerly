"""Unit tests never need a real provider or network — force the stub before ``app.config``
(and anything importing it) is loaded for the first time in this process.
"""

import os

os.environ.setdefault("AI_LLM_PROVIDER", "fake")
os.environ.setdefault("AI_EMBEDDING_PROVIDER", "fake")
