FROM maven:3.9.11-eclipse-temurin-21

COPY images/entrypoint.sh /runner/entrypoint
RUN chmod 0555 /runner/entrypoint \
    && mkdir -p /cache/m2 \
    && chown -R 65532:65532 /cache
ENTRYPOINT ["/runner/entrypoint"]
