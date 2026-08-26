FROM python:3.12.8-slim-bookworm

RUN groupadd --gid 1000 app && useradd --uid 1000 --gid app --shell /bin/false app

WORKDIR /app
COPY apps/ai/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY apps/ai/app/ app/
COPY docs/contracts docs/contracts

USER app
EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
