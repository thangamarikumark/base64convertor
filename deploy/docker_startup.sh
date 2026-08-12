#!/bin/bash
set -euo pipefail

# Base64Convertor / RCM-Forwarder deployment startup script.
#
# Fully environment-agnostic: no host-side mkdir/chown is required. Runtime
# data (file cache, Base64/decoded output, logs) is stored in named Docker
# volumes, which Docker initializes with the correct ownership from the
# image's own directories on first use — the uid/gid drift that used to
# require a manual `chown 100:101` on host bind-mounts (see git history) no
# longer applies, since we no longer bind-mount host directories for this data.
#
# Every path/port the app supports is overridable here, without rebuilding the
# image — see docs/Configuration.md for the full matrix. Defaults match the
# image's own built-in defaults, so running this script with no env vars set
# behaves identically to a bare `docker run <image>`.

IMAGE="${IMAGE:-jfrog2.twixor.com/serviceiqo/base64convertor_rcmforwarder:v8}"
CONTAINER_NAME="${CONTAINER_NAME:-fileconvert2base64}"
HOST_PORT="${HOST_PORT:-5080}"

# ─── Path overrides ──────────────────────────────────────────────────────────
# Kept in sync with the named volume mounts below: whichever path each of
# these resolves to is exactly where the corresponding volume is mounted, so
# overriding one of these still gets you a persistent, correctly-owned
# directory (handled automatically by docker-entrypoint.sh inside the image)
# instead of a volume mounted at the old default while the app writes
# elsewhere.
FILE_CACHE_PATH="${FILE_CACHE_PATH:-/var/lib/base64convertor/file-cache}"
BASE64_OUTPUT_PATH="${BASE64_OUTPUT_PATH:-/var/lib/base64convertor/base64-output}"
LOG_DIR="${LOG_DIR:-/var/log/base64convertor}"

# ─── Port overrides ───────────────────────────────────────────────────────────
# Only passed through if explicitly set — leave unset to use the image's own
# defaults (8080 / 9090). NOTE: if you override SERVER_PORT, also update
# HOST_PORT's target. The image's built-in HEALTHCHECK probes
# MANAGEMENT_PORT (not SERVER_PORT) since /actuator/health only lives on the
# separate management connector — it already tracks MANAGEMENT_PORT
# automatically, no script change needed if you override that one.
SERVER_PORT="${SERVER_PORT:-}"
MANAGEMENT_PORT="${MANAGEMENT_PORT:-}"

# Set when a fronting reverse proxy only forwards one fixed, unchangeable path
# prefix (see docs/Configuration.md, "Context path"). Empty by default — every
# endpoint stays at its literal path.
CONTEXT_PATH="${CONTEXT_PATH:-}"

# Optional external config directory (mounted read-only into /app/config).
# Only needed to override non-path settings (auth token, timeouts, etc.)
# without rebuilding the image — the app boots correctly even if this is
# unset or the directory doesn't exist, since the Dockerfile's ENTRYPOINT
# uses `optional:file:/app/config/`.
CONFIG_DIR="${CONFIG_DIR:-}"

# ─── Optional TLS ─────────────────────────────────────────────────────────────
# Disabled by default (plain HTTP, unchanged behavior). To terminate TLS
# directly on this app instead of at a reverse proxy, set SSL_ENABLED=true and
# TLS_KEYSTORE_FILE to a PKCS12 keystore file on the host — it's bind-mounted
# read-only into the container at the path the app expects. See
# docs/Configuration.md for how to generate/obtain that keystore.
SSL_ENABLED="${SSL_ENABLED:-false}"
TLS_KEYSTORE_FILE="${TLS_KEYSTORE_FILE:-}"
SSL_KEYSTORE_PASSWORD="${SSL_KEYSTORE_PASSWORD:-}"
SSL_KEYSTORE_TYPE="${SSL_KEYSTORE_TYPE:-PKCS12}"

# Optional extra `docker run` args for environment-specific needs. Defaulted
# here to the two internal hosts this deployment's outbound calls need — since
# neither resolves via public DNS in this environment (see docs/Configuration.md
# history). Override EXTRA_DOCKER_ARGS entirely if a different deployment needs
# different hosts, or append more via a fresh env var value — never edit this
# default by hand in the file; pass a new EXTRA_DOCKER_ARGS value instead.
EXTRA_DOCKER_ARGS="${EXTRA_DOCKER_ARGS:---add-host=rcmapi.instaalerts.zone:10.250.112.22 --add-host=twixor.karix.solutions:10.250.55.21}"

docker stop "$CONTAINER_NAME" 2>/dev/null || true
docker rm "$CONTAINER_NAME" 2>/dev/null || true

CONFIG_MOUNT=()
if [[ -n "$CONFIG_DIR" ]]; then
  mkdir -p "$CONFIG_DIR"
  CONFIG_MOUNT=(-v "${CONFIG_DIR}:/app/config:z")
fi

TLS_MOUNT=()
if [[ "$SSL_ENABLED" == "true" ]]; then
  if [[ -z "$TLS_KEYSTORE_FILE" || ! -f "$TLS_KEYSTORE_FILE" ]]; then
    echo "SSL_ENABLED=true but TLS_KEYSTORE_FILE is unset or not a file: '${TLS_KEYSTORE_FILE}'" >&2
    exit 1
  fi
  TLS_MOUNT=(-v "${TLS_KEYSTORE_FILE}:/etc/base64convertor/tls/keystore.p12:ro,z")
fi

ENV_ARGS=(
  -e "FILE_CACHE_PATH=${FILE_CACHE_PATH}"
  -e "BASE64_OUTPUT_PATH=${BASE64_OUTPUT_PATH}"
  -e "LOG_DIR=${LOG_DIR}"
)
[[ -n "$SERVER_PORT" ]] && ENV_ARGS+=(-e "SERVER_PORT=${SERVER_PORT}")
[[ -n "$MANAGEMENT_PORT" ]] && ENV_ARGS+=(-e "MANAGEMENT_PORT=${MANAGEMENT_PORT}")
[[ -n "$CONTEXT_PATH" ]] && ENV_ARGS+=(-e "CONTEXT_PATH=${CONTEXT_PATH}")
if [[ "$SSL_ENABLED" == "true" ]]; then
  ENV_ARGS+=(
    -e "SSL_ENABLED=true"
    -e "SSL_KEYSTORE_PATH=/etc/base64convertor/tls/keystore.p12"
    -e "SSL_KEYSTORE_PASSWORD=${SSL_KEYSTORE_PASSWORD}"
    -e "SSL_KEYSTORE_TYPE=${SSL_KEYSTORE_TYPE}"
  )
fi

# shellcheck disable=SC2086
docker run -d \
  -p "${HOST_PORT}:8080" \
  --name "$CONTAINER_NAME" \
  -v base64convertor_cache:"${FILE_CACHE_PATH}" \
  -v base64convertor_output:"${BASE64_OUTPUT_PATH}" \
  -v base64convertor_logs:"${LOG_DIR}" \
  "${ENV_ARGS[@]}" \
  "${CONFIG_MOUNT[@]}" \
  "${TLS_MOUNT[@]}" \
  ${EXTRA_DOCKER_ARGS} \
  "$IMAGE"
