FROM maven:3.9.11-eclipse-temurin-21

COPY --chmod=0555 images/entrypoint.sh /runner/entrypoint
RUN mkdir -p /cache/m2 && chown -R 65532:65532 /cache
ENTRYPOINT ["/runner/entrypoint"]
