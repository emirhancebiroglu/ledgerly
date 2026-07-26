FROM python:3.12.8-slim-bookworm

RUN groupadd --gid 1000 app && useradd --uid 1000 --gid app --shell /bin/false app

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app/ app/

USER app
EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
