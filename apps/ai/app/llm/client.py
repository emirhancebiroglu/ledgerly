"""The ``LlmClient`` port.

Every model call in this service goes through this interface. Adapters are selected by
configuration, so choosing a provider is a config change rather than a code change — which is what
makes the M5 eval harness able to run two candidates over the same documents.

At M4 the only implementation is :class:`~app.llm.fake.FakeLlmClient`. No real provider is wired
yet; that decision is deliberately deferred to M5, where it can be measured instead of guessed.
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass


class LlmError(RuntimeError):
    """The model could not produce a usable response."""


@dataclass(frozen=True)
class VisionPrompt:
    """A prompt paired with document bytes.

    ``content_type`` is the media type ``api`` established by inspecting the bytes, never the one
    an uploader claimed.
    """

    instruction: str
    content: bytes
    content_type: str


class LlmClient(ABC):
    """Port for the model. Implementations must not raise provider-specific exceptions."""

    @property
    @abstractmethod
    def model_name(self) -> str:
        """Identifier recorded on the proposal, so any output is traceable to what produced it."""

    @abstractmethod
    def complete(self, prompt: str) -> str:
        """Text in, text out."""

    @abstractmethod
    def complete_vision(self, prompt: VisionPrompt) -> str:
        """Document bytes plus an instruction in, text out.

        :raises LlmError: if the model cannot produce a response.
        """
