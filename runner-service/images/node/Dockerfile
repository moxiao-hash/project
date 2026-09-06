FROM node:22-bookworm-slim

COPY images/entrypoint.sh /runner/entrypoint
RUN chmod 0555 /runner/entrypoint \
    && mkdir -p /cache/npm \
    && chown -R 65532:65532 /cache
ENTRYPOINT ["/runner/entrypoint"]
