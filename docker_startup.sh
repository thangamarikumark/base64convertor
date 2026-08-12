#!/bin/bash
set -euo pipefail

# Local/dev convenience runner for the base64convertor image.
#
# No host-side mkdir/chown is required: runtime data (file cache, Base64/decoded
# output, logs) lives in named Docker volumes, which Docker initializes with the
# correct ownership from the image's own directories on first use. Paths can be
# overridden without rebuilding the image via FILE_CACHE_PATH, BASE64_OUTPUT_PATH,
# and LOG_DIR — pass them through with `-e` if you need non-default values.

IMAGE="${IMAGE:-jfrog2.twixor.com/serviceiqo/base64convertor_rcmforwarder:v4}"
CONTAINER_NAME="${CONTAINER_NAME:-fileconvert2base64}"
HOST_PORT="${HOST_PORT:-5080}"

docker stop "$CONTAINER_NAME" 2>/dev/null || true
docker rm "$CONTAINER_NAME" 2>/dev/null || true

docker run -d \
  -p "${HOST_PORT}:8080" \
  --name "$CONTAINER_NAME" \
  -v base64convertor_cache:/var/lib/base64convertor/file-cache \
  -v base64convertor_output:/var/lib/base64convertor/base64-output \
  -v base64convertor_logs:/var/log/base64convertor \
  "$IMAGE"
