FROM python:3.12-slim

RUN pip install --no-cache-dir pytest ruff
COPY --chmod=0555 images/entrypoint.sh /runner/entrypoint
RUN mkdir -p /cache && chown -R 65532:65532 /cache
ENTRYPOINT ["/runner/entrypoint"]
