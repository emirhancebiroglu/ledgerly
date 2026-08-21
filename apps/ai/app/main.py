import logging
from datetime import datetime
from uuid import UUID

from fastapi import Depends, FastAPI, File, Form, HTTPException, Request, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field, StrictInt, field_validator

from app.anomaly.anomaly import AnomalyFailedError, AnomalyService
from app.categorization.categorization import CategorizationFailedError, CategorizationService
from app.config import settings
from app.contracts import EMBED_POLICY_REQUEST_SCHEMA, EXTRACT_REQUEST_SCHEMA, load_schema
from app.embeddings import EmbeddingClient, FakeEmbeddingClient, LiteLlmEmbeddingClient
from app.extraction import ExtractionFailedError, ExtractionService
from app.llm import FakeLlmClient, LiteLlmClient, ResilientLlmClient
from app.llm.client import LlmClient
from app.policy.chunking import EmptyDocumentError
from app.observability import configure_logging, reset_correlation_id, set_correlation_id
from app.policy.embedding import PolicyEmbeddingFailedError, PolicyEmbeddingService
from app.rate_limit import AiRateLimiter, RateLimitExceeded, RateLimitUnavailable
from app.service_auth import is_cost_bearing_agent_request, require_service_auth

configure_logging()
logger = logging.getLogger(__name__)

app = FastAPI(
    title=settings.service_name,
    version=settings.service_version,
    docs_url="/docs" if settings.enable_docs else None,
    redoc_url="/redoc" if settings.enable_docs else None,
    openapi_url="/openapi.json" if settings.enable_docs else None,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origins,
    allow_methods=["GET", "POST"],
    allow_headers=["*"],
)
app.state.rate_limiter = AiRateLimiter(
    settings.rate_limit_redis_url,
    settings.rate_limit_max_requests,
    settings.rate_limit_window_seconds,
)


@app.middleware("http")
async def request_logging(request: Request, call_next):
    correlation_id = request.headers.get("X-Correlation-Id")
    if correlation_id is not None:
        correlation_id = correlation_id.replace("\r", "").replace("\n", "")[:128]
    token = set_correlation_id(correlation_id)
    try:
        response = await call_next(request)
        logger.info("HTTP request completed method=%s path=%s status=%s", request.method, request.url.path, response.status_code)
        return response
    finally:
        reset_correlation_id(token)


@app.middleware("http")
async def service_authentication(request: Request, call_next):
    rejection = await require_service_auth(request, settings.service_token)
    if rejection is not None:
        return rejection
    return await call_next(request)


async def enforce_agent_rate_limit(request: Request) -> None:
    """Charge only requests that already passed FastAPI and endpoint validation."""
    if not settings.rate_limit_enabled or not is_cost_bearing_agent_request(request):
        return
    try:
        await request.app.state.rate_limiter.check(request.url.path)
    except RateLimitExceeded as error:
        raise HTTPException(
            status_code=429,
            detail="Rate limit exceeded",
            headers={"Retry-After": str(error.retry_after_seconds)},
        ) from error
    except RateLimitUnavailable as error:
        raise HTTPException(
            status_code=503, detail="Rate limiting is temporarily unavailable"
        ) from error


def _load_supported_content_types() -> frozenset[str]:
    """Media types `ai` will attempt to read — read from the shared contract, not restated here."""
    try:
        enum = load_schema(EXTRACT_REQUEST_SCHEMA)["properties"]["content_type"]["enum"]
    except KeyError as error:
        raise RuntimeError(
            f"{EXTRACT_REQUEST_SCHEMA} has no properties.content_type.enum — "
            "cannot derive the accepted media-type list"
        ) from error
    return frozenset(enum)


def _load_supported_policy_content_types() -> frozenset[str]:
    try:
        enum = load_schema(EMBED_POLICY_REQUEST_SCHEMA)["properties"]["content_type"]["enum"]
    except KeyError as error:
        raise RuntimeError(
            f"{EMBED_POLICY_REQUEST_SCHEMA} has no properties.content_type.enum — "
            "cannot derive the accepted media-type list"
        ) from error
    return frozenset(enum)


SUPPORTED_CONTENT_TYPES = _load_supported_content_types()
SUPPORTED_POLICY_CONTENT_TYPES = _load_supported_policy_content_types()


def get_llm_client() -> LlmClient:
    """Adapter selection lives here so a provider change is configuration, not code."""
    if settings.llm_provider == "fake":
        return FakeLlmClient()
    if settings.llm_provider == "litellm":
        inner = LiteLlmClient(
            model=settings.llm_model,
            api_key=settings.llm_api_key,
            timeout_seconds=settings.llm_timeout_seconds,
            api_base=settings.llm_api_base,
            supports_native_pdf=settings.llm_supports_native_pdf,
            thinking_enabled=settings.llm_enable_thinking,
        )
        return ResilientLlmClient(
            inner,
            max_retries=settings.llm_max_retries,
            failure_threshold=settings.llm_circuit_breaker_failure_threshold,
            cooldown_seconds=settings.llm_circuit_breaker_cooldown_seconds,
        )
    raise RuntimeError(f"Unknown LLM provider: {settings.llm_provider}")


def get_vendor_verification_client() -> LlmClient:
    if settings.llm_provider == "fake":
        return FakeLlmClient()
    if settings.llm_provider == "litellm":
        return ResilientLlmClient(
            LiteLlmClient(
                model=settings.vendor_verification_model,
                api_key=settings.llm_api_key,
                timeout_seconds=settings.llm_timeout_seconds,
                api_base=settings.llm_api_base,
                supports_native_pdf=settings.llm_supports_native_pdf,
                thinking_enabled=False,
            ),
            max_retries=settings.llm_max_retries,
            failure_threshold=settings.llm_circuit_breaker_failure_threshold,
            cooldown_seconds=settings.llm_circuit_breaker_cooldown_seconds,
        )
    raise RuntimeError(f"Unknown LLM provider: {settings.llm_provider}")


def get_extraction_service() -> ExtractionService:
    return ExtractionService(get_llm_client(), get_vendor_verification_client())


def get_embedding_client() -> EmbeddingClient:
    if settings.embedding_provider == "fake":
        return FakeEmbeddingClient()
    if settings.embedding_provider == "litellm":
        return LiteLlmEmbeddingClient(
            model=settings.embedding_model,
            api_key=settings.embedding_api_key,
            dimensions=settings.embedding_dimensions,
            timeout_seconds=settings.embedding_timeout_seconds,
            api_base=settings.embedding_api_base,
        )
    raise RuntimeError(f"Unknown embedding provider: {settings.embedding_provider}")


def get_policy_embedding_service() -> PolicyEmbeddingService:
    return PolicyEmbeddingService(get_embedding_client())


def get_categorization_service() -> CategorizationService:
    return CategorizationService(get_llm_client())


def get_anomaly_service() -> AnomalyService:
    return AnomalyService(get_llm_client())


class EmbedQueryRequest(BaseModel):
    text: str = Field(min_length=1)
    correlation_id: str | None = None


class CategorizeChunk(BaseModel):
    chunk_text: str = Field(min_length=1)


class CategorizeRequestBody(BaseModel):
    document_id: str
    vendor: str | None = None
    currency: str
    total_minor: int
    document_date: str | None = None
    categories: list[str] = Field(min_length=1)
    policy_chunks: list[CategorizeChunk] = Field(default_factory=list)
    correlation_id: str | None = None


class AnomalyHistoryItem(BaseModel):
    model_config = ConfigDict(extra="forbid")

    amount_minor: StrictInt = Field(gt=0)
    posted_at: datetime

    @field_validator("posted_at", mode="before")
    @classmethod
    def posted_at_must_be_a_datetime_string(cls, value: object) -> object:
        if not isinstance(value, str):
            raise ValueError("posted_at must be a date-time string")
        return value

    @field_validator("posted_at")
    @classmethod
    def posted_at_must_include_timezone(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("posted_at must include an offset")
        return value


class AnomalyBudget(BaseModel):
    model_config = ConfigDict(extra="forbid")

    period: str = Field(pattern=r"^[0-9]{4}-(0[1-9]|1[0-2])$")
    limit_minor: StrictInt = Field(gt=0)
    spent_minor: StrictInt = Field(ge=0)


class AnomalyRequestBody(BaseModel):
    model_config = ConfigDict(extra="forbid")

    expense_id: UUID
    category_id: UUID
    currency: str = Field(pattern=r"^[A-Z]{3}$")
    amount_minor: StrictInt = Field(gt=0)
    history: list[AnomalyHistoryItem] = Field(max_length=1000)
    budget: AnomalyBudget | None
    correlation_id: str | None = Field(default=None, max_length=128)


@app.get("/health")
def health() -> dict:
    return {"service": settings.service_name, "version": settings.service_version, "status": "UP"}


@app.post("/extract")
async def extract(
    request: Request,
    file: UploadFile = File(...),
    document_id: str = Form(...),
    content_type: str = Form(...),
    correlation_id: str | None = Form(default=None),
    service: ExtractionService = Depends(get_extraction_service),
) -> dict:
    """Document bytes in, a schema-valid ``ExtractionProposal`` out.

    The proposal is advisory. `api` validates it against its own rules and decides whether anything
    is posted — see ``docs/architecture.md`` constraint C5.
    """
    if content_type not in SUPPORTED_CONTENT_TYPES:
        raise HTTPException(status_code=422, detail="Unsupported document content type")

    content = await file.read()

    if len(content) > settings.max_document_bytes:
        raise HTTPException(status_code=413, detail="Document exceeds the maximum accepted size")

    await enforce_agent_rate_limit(request)

    try:
        return service.extract(document_id, content, content_type)
    except ExtractionFailedError as error:
        # A document this service cannot read is a bad request, not a server fault: returning 500
        # would tell `api` to retry something that will fail identically every time.
        logger.info("Extraction failed documentId=%s exceptionType=%s", document_id, type(error).__name__)
        raise HTTPException(status_code=422, detail=str(error)) from error


@app.post("/embed-policy")
async def embed_policy(
    request: Request,
    file: UploadFile = File(...),
    policy_document_id: str = Form(...),
    content_type: str = Form(...),
    correlation_id: str | None = Form(default=None),
    service: PolicyEmbeddingService = Depends(get_policy_embedding_service),
) -> dict:
    """Policy document bytes in, a schema-valid ``EmbedPolicyResponse`` out.

    `api` persists the returned chunks as `policy_chunk` rows with pgvector embeddings.
    """
    if content_type not in SUPPORTED_POLICY_CONTENT_TYPES:
        raise HTTPException(status_code=422, detail="Unsupported policy document content type")

    content = await file.read()

    if len(content) > settings.max_document_bytes:
        raise HTTPException(status_code=413, detail="Document exceeds the maximum accepted size")

    await enforce_agent_rate_limit(request)

    try:
        return service.embed_policy(policy_document_id, content)
    except (PolicyEmbeddingFailedError, EmptyDocumentError) as error:
        logger.info("Policy embedding failed documentId=%s exceptionType=%s", policy_document_id, type(error).__name__)
        raise HTTPException(status_code=422, detail=str(error)) from error


@app.post("/embed-query")
async def embed_query(
    request: Request,
    body: EmbedQueryRequest,
    embedding_client: EmbeddingClient = Depends(get_embedding_client),
) -> dict:
    """A single embedding vector for `api` to use in a pgvector nearest-neighbor search.

    Uses the same embedding model as `POST /embed-policy`, so the returned vector is directly
    comparable to `policy_chunk.embedding`.
    """
    await enforce_agent_rate_limit(request)
    vectors = embedding_client.embed([body.text])
    return {
        "model": embedding_client.model_name,
        "embedding_dimensions": embedding_client.dimensions,
        "embedding": vectors[0],
    }


@app.post("/categorize")
async def categorize(
    request: Request,
    body: CategorizeRequestBody,
    service: CategorizationService = Depends(get_categorization_service),
) -> dict:
    """Extracted fields plus retrieved policy chunks in, a category classification out.

    Advisory only — `api` decides whether confidence clears the posting threshold (M6 T7).
    """
    await enforce_agent_rate_limit(request)
    try:
        return service.categorize(
            document_id=body.document_id,
            vendor=body.vendor,
            currency=body.currency,
            total_minor=body.total_minor,
            document_date=body.document_date,
            categories=body.categories,
            policy_chunks=[chunk.chunk_text for chunk in body.policy_chunks],
        )
    except CategorizationFailedError as error:
        logger.info("Categorization failed documentId=%s exceptionType=%s", body.document_id, type(error).__name__)
        raise HTTPException(status_code=422, detail=str(error)) from error


@app.post("/anomaly")
async def anomaly(
    request: Request,
    body: AnomalyRequestBody,
    service: AnomalyService = Depends(get_anomaly_service),
) -> dict:
    """Return deterministic anomaly facts and an advisory qualitative explanation."""
    await enforce_agent_rate_limit(request)
    try:
        return service.analyze(
            expense_id=str(body.expense_id),
            amount_minor=body.amount_minor,
            history=[item.model_dump(mode="json") for item in body.history],
            budget=body.budget.model_dump(mode="json") if body.budget is not None else None,
        )
    except AnomalyFailedError as error:
        logger.info("Anomaly assessment failed expenseId=%s exceptionType=%s", body.expense_id, type(error).__name__)
        raise HTTPException(status_code=422, detail=str(error)) from error


@app.exception_handler(404)
async def not_found_handler(request: Request, exc: Exception) -> JSONResponse:
    return JSONResponse(status_code=404, content={"detail": "Resource not found"})


@app.exception_handler(Exception)
async def unhandled_exception_handler(request: Request, exc: Exception) -> JSONResponse:
    logger.error("Unhandled exception type=%s status=500", type(exc).__name__)
    return JSONResponse(status_code=500, content={"detail": "Unexpected error"})
