FROM node:22-bookworm-slim

COPY --chmod=0555 images/entrypoint.sh /runner/entrypoint
RUN mkdir -p /cache/npm && chown -R 65532:65532 /cache
ENTRYPOINT ["/runner/entrypoint"]
