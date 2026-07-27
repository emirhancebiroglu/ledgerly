import logging

from fastapi import Depends, FastAPI, File, Form, HTTPException, Request, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from app.config import settings
from app.contracts import EXTRACT_REQUEST_SCHEMA, load_schema
from app.extraction import ExtractionFailedError, ExtractionService
from app.llm import FakeLlmClient, LiteLlmClient, ResilientLlmClient
from app.llm.client import LlmClient

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


SUPPORTED_CONTENT_TYPES = _load_supported_content_types()


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
        )
        return ResilientLlmClient(
            inner,
            max_retries=settings.llm_max_retries,
            failure_threshold=settings.llm_circuit_breaker_failure_threshold,
            cooldown_seconds=settings.llm_circuit_breaker_cooldown_seconds,
        )
    raise RuntimeError(f"Unknown LLM provider: {settings.llm_provider}")


def get_extraction_service() -> ExtractionService:
    return ExtractionService(get_llm_client())


@app.get("/health")
def health() -> dict:
    return {"service": settings.service_name, "version": settings.service_version, "status": "UP"}


@app.post("/extract")
async def extract(
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

    try:
        return service.extract(document_id, content, content_type)
    except ExtractionFailedError as error:
        # A document this service cannot read is a bad request, not a server fault: returning 500
        # would tell `api` to retry something that will fail identically every time.
        logger.info("Extraction failed for document %s: %s", document_id, error)
        raise HTTPException(status_code=422, detail=str(error)) from error


@app.exception_handler(404)
async def not_found_handler(request: Request, exc: Exception) -> JSONResponse:
    return JSONResponse(status_code=404, content={"detail": "Resource not found"})


@app.exception_handler(Exception)
async def unhandled_exception_handler(request: Request, exc: Exception) -> JSONResponse:
    return JSONResponse(status_code=500, content={"detail": "Unexpected error"})
