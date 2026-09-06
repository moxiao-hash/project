FROM python:3.12-slim

RUN pip install --no-cache-dir pytest ruff
COPY images/entrypoint.sh /runner/entrypoint
RUN chmod 0555 /runner/entrypoint \
    && mkdir -p /cache \
    && chown -R 65532:65532 /cache
ENTRYPOINT ["/runner/entrypoint"]
