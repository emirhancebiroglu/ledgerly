from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from app.config import settings

app = FastAPI(
    title=settings.service_name,
    version=settings.service_version,
    docs_url="/docs" if settings.enable_docs else None,
    redoc_url="/redoc" if settings.enable_docs else None,
    openapi_url="/openapi.json" if settings.enable_docs else None,
)


@app.get("/health")
def health() -> dict:
    return {"service": settings.service_name, "version": settings.service_version, "status": "UP"}


@app.exception_handler(404)
async def not_found_handler(request: Request, exc: Exception) -> JSONResponse:
    return JSONResponse(status_code=404, content={"detail": "Resource not found"})


@app.exception_handler(Exception)
async def unhandled_exception_handler(request: Request, exc: Exception) -> JSONResponse:
    return JSONResponse(status_code=500, content={"detail": "Unexpected error"})
