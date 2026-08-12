FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache ca-certificates wget tzdata su-exec && update-ca-certificates

ENV TZ=Asia/Kolkata

# Parameterized runtime identity — override at build time only if the target
# platform requires a specific UID/GID (e.g. to match an externally managed
# volume). Defaults are a normal unprivileged, non-system-range account.
ARG APP_UID=10001
ARG APP_GID=10001

# Single standardized runtime-data path convention, matching
# docs/Configuration.md. Every path is also overridable at *container run
# time* via environment variables of the same name — these ENV defaults just
# need to exist and be writable so the image runs correctly with zero extra
# flags ("docker run <image>" alone is enough), AND custom overrides are
# handled automatically too (see docker-entrypoint.sh).
ENV FILE_CACHE_PATH=/var/lib/base64convertor/file-cache \
    BASE64_OUTPUT_PATH=/var/lib/base64convertor/base64-output \
    LOG_DIR=/var/log/base64convertor

WORKDIR /app

# /app/config is an OPTIONAL external properties mount (see ENTRYPOINT below);
# it is not required for the app to boot. Every directory created here is
# actually referenced by the application — no stale/unused paths.
RUN addgroup -g ${APP_GID} -S appgroup && \
    adduser -u ${APP_UID} -S appuser -G appgroup && \
    mkdir -p /app/config "$FILE_CACHE_PATH" "$BASE64_OUTPUT_PATH" "$LOG_DIR" && \
    chown -R appuser:appgroup /app "$FILE_CACHE_PATH" "$BASE64_OUTPUT_PATH" "$LOG_DIR"

ARG JAR_FILE=target/base64convertor-0.0.1-SNAPSHOT.jar

COPY --chown=appuser:appgroup ${JAR_FILE} app.jar
COPY --chown=root:root docker-entrypoint.sh /docker-entrypoint.sh
RUN chmod +x /docker-entrypoint.sh

# The container process starts as root (image default) so the entrypoint can
# mkdir/chown whatever path FILE_CACHE_PATH/BASE64_OUTPUT_PATH/LOG_DIR resolve
# to for this specific run — including brand-new bind mounts or named volumes
# that don't match the build-time defaults above. The entrypoint immediately
# execs the JVM as the unprivileged `appuser` via su-exec — the application
# itself never runs as root, satisfying non-root execution in practice while
# still supporting arbitrary custom paths automatically.
ENTRYPOINT ["/docker-entrypoint.sh"]

EXPOSE 8080

# Probes the actuator/management port (MANAGEMENT_PORT, default 9090), NOT the
# public API port — /actuator/health is only exposed there (management.server.port
# is a separate connector; see application.properties). Uses shell-form CMD
# specifically so ${MANAGEMENT_PORT} is expanded against the container's actual
# runtime environment at each check, not baked in at build time. Assumes the
# management port stays plain HTTP (the application default, see
# MANAGEMENT_SSL_ENABLED in docs/Configuration.md) — if that's overridden to
# HTTPS, update this to `wget --no-check-certificate https://...`.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
CMD wget --no-verbose --tries=1 --spider http://localhost:${MANAGEMENT_PORT:-9090}/actuator/health || exit 1

# "optional:" means a missing/empty /app/config mount no longer prevents startup —
# the bundled, environment-variable-driven defaults are sufficient on their own.
CMD ["java", \
"-XX:+UseContainerSupport", \
"-XX:MaxRAMPercentage=75.0", \
"-XX:+ExitOnOutOfMemoryError", \
"-jar", \
"app.jar", \
"--spring.config.additional-location=optional:file:/app/config/"]
