# Configuration — Environment Variables and Path Conventions

This application follows the [12-factor app](https://12factor.net/config) config model:
**environment variables are the primary way to configure it.** The same jar / Docker
image runs unmodified on a developer laptop, in Docker Compose, in Kubernetes/OpenShift,
and in UAT/production — only environment variables differ.

## Configuration matrix

| Property                     | Default value                              | Environment variable | Notes |
|-------------------------------|--------------------------------------------|-----------------------|-------|
| `file.cache.path`             | `/var/lib/base64convertor/file-cache`       | `FILE_CACHE_PATH`     | Temp download cache for `/api/files/convert`, `/convert/single`. Auto-created at startup; startup fails fast if not writable. |
| `app.base64.output-path`      | `/var/lib/base64convertor/base64-output`    | `BASE64_OUTPUT_PATH`  | `.b64` files and decoded output (`/save-decoded`, `/callback`, `/pdf/convert/*`). Auto-created at startup. |
| Log directory (`log4j2-spring.xml`) | `/var/log/base64convertor`             | `LOG_DIR`             | `pdf.log`, `http.log`, `error.log` and their `archive/` rollovers. Auto-created before Spring Boot / Log4j2 initialize. |
| `server.port`                 | `8080`                                      | `SERVER_PORT`         | Public API port. |
| `management.server.port`      | `9090`                                      | `MANAGEMENT_PORT`     | Actuator/Prometheus port, kept separate from the public API port. |
| `server.ssl.enabled`          | `false`                                     | `SSL_ENABLED`         | Opt-in TLS on the public API port. See [TLS](#tls-optional) below. |
| `server.ssl.key-store`        | `/etc/base64convertor/tls/keystore.p12`     | `SSL_KEYSTORE_PATH`   | Only read when `SSL_ENABLED=true`. |
| `server.ssl.key-store-password` | *(none)*                                  | `SSL_KEYSTORE_PASSWORD` | Required when `SSL_ENABLED=true`. |
| `server.ssl.key-store-type`   | `PKCS12`                                    | `SSL_KEYSTORE_TYPE`   | `PKCS12` or `JKS`. |
| `management.server.ssl.enabled` | `false`                                   | `MANAGEMENT_SSL_ENABLED` | Kept independent of `SSL_ENABLED` — see the TLS section for why. |
| `server.servlet.context-path` | *(none — root)*                             | `CONTEXT_PATH`        | See [Context path](#context-path-behind-a-fixed-proxy-prefix) below. Does not affect the management/actuator port. |

Every other application setting (auth token, timeouts, retry policy, retention days,
PDF protection limits, etc.) already follows normal Spring Boot property resolution and
can be overridden the same way, e.g. `APP_HTTP_CONNECT_TIMEOUT_SECONDS` for
`app.http.connect-timeout-seconds` (Spring Boot's [relaxed
binding](https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.typesafe-configuration-properties.relaxed-binding)).
This document only tracks the *path-shaped* properties, since those are the ones that
used to be hardcoded per environment.

## How overriding works

Nothing needs to be rebuilt or redeployed to change a path. Set the environment variable
before starting the process:

```bash
# Local jar
FILE_CACHE_PATH=/data/cache BASE64_OUTPUT_PATH=/data/output LOG_DIR=/data/logs \
  java -jar target/base64convertor-0.0.1-SNAPSHOT.jar

# Docker
docker run \
  -e FILE_CACHE_PATH=/data/cache \
  -e BASE64_OUTPUT_PATH=/data/output \
  -e LOG_DIR=/data/logs \
  -v mydata:/data \
  <image>
```

If a variable isn't set, the built-in default applies — the app never requires an
external properties file to boot.

## Directory creation and fail-fast startup

`com.twixor.base64convertor.common.config.RuntimeDirectoryBootstrap` creates
`FILE_CACHE_PATH`, `BASE64_OUTPUT_PATH`, and `LOG_DIR` (with `Files.createDirectories`)
from `main()`, **before** `SpringApplication.run(...)` is called — this is what
guarantees `LOG_DIR` exists before Log4j2 opens its RollingFile appenders, since Log4j2
initializes earlier than any Spring bean.

If a directory can't be created, or exists but isn't writable by the process user, the
application throws immediately with a message naming the exact path, the environment
variable that controls it, and what to do about it — it does not start in a
half-working state with silently broken logging or caching.

`FileConversionService`'s own `@PostConstruct` directory check (for `file.cache.path`)
was similarly changed to throw instead of only logging a warning, as defense in depth.

## Path convention

All runtime-writable data lives under two standard Linux locations:

- `/var/lib/base64convertor/` — persistent application data (cache, Base64/decoded output)
- `/var/log/base64convertor/` — logs

This replaces three previously-inconsistent, ad hoc conventions found in the codebase:
`/opt/base64convertor/data/...` (dev), `/app/cache` (prod deploy properties), and
`/opt/fileconvertor` (root-level `docker_startup.sh` — a naming drift/typo relative to
every other location in the repo). None of these are referenced anywhere anymore.

## Backward compatibility

Existing deployments that relied on `/app/cache` (via the old
`deploy/application.properties.prod` or the old `docker_startup.sh` bind mounts) are not
broken, but do need one change: set `FILE_CACHE_PATH=/app/cache` and
`BASE64_OUTPUT_PATH=/app/cache/base64-output` as environment variables to keep using
that exact location. No code or default changed silently underneath an existing mount —
the old path is simply no longer the *default*.

## Docker image

- The image now creates and `chown`s `/var/lib/base64convertor/{file-cache,base64-output}`
  and `/var/log/base64convertor` at build time, so it runs correctly out of the box with
  no extra flags: `docker run <image>` alone is a fully working container.
- `--spring.config.additional-location` uses the `optional:` prefix, so the
  `/app/config` mount is no longer required for startup — it remains available for
  operators who want to override non-path settings via a mounted properties file.
- Container UID/GID are build args (`APP_UID`, `APP_GID`, default `10001:10001`) instead
  of hardcoded values, so a platform that requires a specific UID can supply
  `--build-arg APP_UID=... --build-arg APP_GID=...` without editing the Dockerfile.
- Runtime data uses named Docker volumes (`base64convertor_cache`, `base64convertor_output`,
  `base64convertor_logs`) rather than host bind mounts — Docker initializes a named
  volume's ownership from the image's own directory on first use, which is what removes
  the need for the old manual `chown 100:101` step on the host.

## Context path (behind a fixed proxy prefix)

Empty by default — every controller is reachable at its literal path (e.g.
`/api/files/pdf/protect`, `/api/test/ping`).

Set `CONTEXT_PATH` when a reverse proxy in front of this app only forwards one fixed
path prefix and can't be reconfigured to rewrite or add more locations — e.g. an nginx
config with a single, unchangeable block like:
```nginx
location /api/files/convert/ {
    proxy_pass http://backend_fileconverter;
}
```
Since `proxy_pass` here has no trailing path component, nginx forwards the **full
original request URI unchanged** to the backend. Setting `CONTEXT_PATH` to that same
prefix means Spring strips it before routing, so every existing endpoint becomes
reachable at `CONTEXT_PATH` + its current path — with no nginx changes needed:

```bash
CONTEXT_PATH=/api/files/convert java -jar target/base64convertor-0.0.1-SNAPSHOT.jar
```

| Original path | Path once `CONTEXT_PATH=/api/files/convert` is set |
|---|---|
| `/api/files/pdf/protect` | `/api/files/convert/api/files/pdf/protect` |
| `/api/test/ping` | `/api/files/convert/api/test/ping` |
| `/api/files/convert/single` | `/api/files/convert/api/files/convert/single` |

Yes, that last one looks redundant (`convert` appears twice) — that's an unavoidable
consequence of aliasing the whole app under a proxy path that happens to share a
segment name with one of the app's own routes. It's still correct: nginx matches on
the literal path prefix, not on any semantic meaning of "convert".

This only affects the public API port (`SERVER_PORT`) — the management/actuator port
(`MANAGEMENT_PORT`) is a fully separate embedded server and is never affected by
`CONTEXT_PATH`; `/actuator/health` stays at the root of that port regardless.

## TLS (optional)

Disabled by default — the app serves plain HTTP on `SERVER_PORT`, unchanged from
before. To terminate TLS directly on the app's embedded Tomcat instead of (or as well
as) at a reverse proxy/load balancer in front of it, set:

```bash
SSL_ENABLED=true
SSL_KEYSTORE_PATH=/path/to/keystore.p12   # PKCS12 keystore containing your cert + key
SSL_KEYSTORE_PASSWORD=<password>
SSL_KEYSTORE_TYPE=PKCS12                   # or JKS
```

**Generating a keystore:**

- *Self-signed, for local/dev testing only:*
  ```bash
  keytool -genkeypair -alias base64convertor -keyalg RSA -keysize 2048 -storetype PKCS12 \
    -keystore keystore.p12 -validity 365 -storepass <password> \
    -dname "CN=your-hostname, OU=Dev, O=YourOrg, L=City, ST=State, C=IN"
  ```
- *From a real certificate issued by a CA* (you'll typically have a `.crt`/`.pem` and a
  `.key` file): combine them into a PKCS12 keystore with `openssl`:
  ```bash
  openssl pkcs12 -export -in certificate.crt -inkey private.key \
    -out keystore.p12 -name base64convertor -password pass:<password>
  ```
  Include an intermediate/chain file too if your CA provides one: add
  `-certfile chain.pem` to the command above.

**Local jar:**
```bash
SSL_ENABLED=true SSL_KEYSTORE_PATH=/path/to/keystore.p12 SSL_KEYSTORE_PASSWORD=<password> \
  java -jar target/base64convertor-0.0.1-SNAPSHOT.jar
```
Once enabled, plain `http://` requests to the same port get a 400 ("This combination
of host and port requires TLS") — connect via `https://` instead.

**Docker / `deploy/docker_startup.sh`:** the keystore *file* is bind-mounted in (it's a
single file, not a writable data directory, so it isn't a named volume like the other
paths):
```bash
SSL_ENABLED=true \
TLS_KEYSTORE_FILE=/host/path/to/keystore.p12 \
SSL_KEYSTORE_PASSWORD=<password> \
IMAGE=jfrog2.twixor.com/serviceiqo/base64convertor_rcmforwarder:v7 \
bash deploy/docker_startup.sh
```
The script mounts `TLS_KEYSTORE_FILE` read-only at the container path the app expects
and fails fast with a clear message if `SSL_ENABLED=true` but the file doesn't exist —
it won't silently start with TLS half-configured.

**Why the management/actuator port (`MANAGEMENT_PORT`, default `9090`) stays plain HTTP
even when `SSL_ENABLED=true`:** Spring Boot's management server otherwise silently
inherits `server.ssl.enabled`, which then either fails to start (no management keystore
configured) or — worse — succeeds by picking up an unrelated, stray keystore if one
happens to exist at Java's default lookup location on the host. `management.server.ssl.enabled`
is pinned to its own `MANAGEMENT_SSL_ENABLED` variable (default `false`) specifically to
prevent that. Set `MANAGEMENT_SSL_ENABLED=true` explicitly if you also want TLS on the
actuator port (reusing the same keystore variables).

**Docker `HEALTHCHECK` note:** it probes `MANAGEMENT_PORT` over plain HTTP (matching the
default above). If you set `MANAGEMENT_SSL_ENABLED=true`, update the `HEALTHCHECK` line
in the `Dockerfile` to use `https://` (and `wget --no-check-certificate` for a
self-signed cert) — otherwise the health check itself will start failing.

## First run on a fresh checkout (bare metal, no Docker)

If `/var/lib/base64convertor` and `/var/log/base64convertor` aren't writable by the user
running the jar (common on a shared box without root), either:

- create them once and `chown` them to that user, or
- override the three environment variables to point at a location the user already owns,
  e.g. `FILE_CACHE_PATH=$HOME/base64convertor/file-cache`.

The application will tell you exactly which path and variable is the problem if this
step is skipped — it does not fail silently.
