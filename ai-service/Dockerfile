FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app
RUN groupadd --system studypilot \
    && useradd --system --gid studypilot --home-dir /home/studypilot --create-home studypilot \
    && mkdir -p /data /home/studypilot/.cache/huggingface \
    && chown -R studypilot:studypilot /data /home/studypilot

COPY pyproject.toml README.md ./
COPY app ./app
RUN pip install .

USER studypilot
EXPOSE 8000
HEALTHCHECK --interval=10s --timeout=5s --start-period=20s --retries=5 \
    CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=3)"]
CMD ["python", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1"]
