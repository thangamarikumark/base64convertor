#!/bin/sh
set -eu

# Runs as root (the container's default user) purely to make whatever paths
# are configured for THIS run — default or custom, via FILE_CACHE_PATH /
# BASE64_OUTPUT_PATH / LOG_DIR — writable by the unprivileged appuser, then
# immediately drops privileges before executing the JVM. This is what lets
# `docker run -e FILE_CACHE_PATH=/data/cache ...` work automatically even
# when /data is a brand-new bind mount or named volume Docker just created
# as root:root — without this step, only the three baked-in default paths
# (already chowned at image build time) would be writable, and any custom
# override would fail fast (by design) instead of self-healing.
#
# The application process itself still runs as appuser, never as root —
# only this bootstrap step runs with elevated privileges, and only for as
# long as it takes to mkdir/chown.

: "${FILE_CACHE_PATH:=/var/lib/base64convertor/file-cache}"
: "${BASE64_OUTPUT_PATH:=/var/lib/base64convertor/base64-output}"
: "${LOG_DIR:=/var/log/base64convertor}"

mkdir -p "$FILE_CACHE_PATH" "$BASE64_OUTPUT_PATH" "$LOG_DIR"
chown -R appuser:appgroup "$FILE_CACHE_PATH" "$BASE64_OUTPUT_PATH" "$LOG_DIR"

exec su-exec appuser:appgroup "$@"
