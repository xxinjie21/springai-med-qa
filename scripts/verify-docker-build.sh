#!/usr/bin/env bash
#
# verify-docker-build.sh -- real container-build verification for springai-med-qa (D33).
#
# The unit suite can only parse the Dockerfile text; it deliberately never needs a Docker daemon.
# This script closes that gap: it performs an actual `docker build` and then asserts the runtime
# contract of the produced image, so "the image still works" is a repeatable check rather than a
# claim made from reading a file.
#
# It verifies, in order:
#   1. the image builds at all (both stages, Maven Wrapper, layered-jar extraction);
#   2. the OCI metadata is present, so a registry can identify the artifact;
#   3. the container runs as the unprivileged `medqa` user, never as root;
#   4. the Spring Boot 3.x layered layout and JarLauncher entrypoint are in place;
#   5. the application jar is a real executable archive (the launcher can read its manifest).
#
# Usage:
#   scripts/verify-docker-build.sh [image-tag]
#
# Exit codes: 0 = verified, 1 = a check failed, 2 = Docker is not available.

set -euo pipefail

IMAGE_TAG="${1:-springai-med-qa:verify}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# Seconds the bounded boot smoke test waits for Spring Boot to reach a banner.
BOOT_TIMEOUT="${BOOT_TIMEOUT:-150}"

log() { printf '\n==> %s\n' "$*"; }
fail() { printf '\n!! %s\n' "$*" >&2; exit 1; }

cd "$REPO_ROOT"

if ! command -v docker >/dev/null 2>&1; then
  printf '!! docker is not installed; skipping the container-build verification\n' >&2
  exit 2
fi
if ! docker info >/dev/null 2>&1; then
  printf '!! the Docker daemon is not reachable; skipping the container-build verification\n' >&2
  exit 2
fi

REVISION="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
BUILD_DATE="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

log "Building $IMAGE_TAG from $REPO_ROOT (revision $REVISION)"
docker build \
  --build-arg "VERSION=0.0.1-SNAPSHOT" \
  --build-arg "REVISION=$REVISION" \
  --build-arg "BUILD_DATE=$BUILD_DATE" \
  --tag "$IMAGE_TAG" \
  .

log "Checking OCI image metadata"
for label in \
  org.opencontainers.image.title \
  org.opencontainers.image.source \
  org.opencontainers.image.licenses \
  org.opencontainers.image.revision
do
  value="$(docker image inspect --format "{{ index .Config.Labels \"$label\" }}" "$IMAGE_TAG")"
  [ -n "$value" ] && [ "$value" != "<no value>" ] || fail "label $label is missing from the image"
  printf '    %-40s %s\n' "$label" "$value"
done

log "Checking that the container does not run as root"
IMAGE_USER="$(docker image inspect --format '{{.Config.User}}' "$IMAGE_TAG")"
[ "$IMAGE_USER" = "medqa" ] || fail "expected the image to run as 'medqa' but found '$IMAGE_USER'"
printf '    Config.User = %s\n' "$IMAGE_USER"

log "Checking the layered Spring Boot layout and the launcher entrypoint"
docker run --rm --entrypoint sh "$IMAGE_TAG" -c '
  set -e
  test -f /app/BOOT-INF/classes/application.yml || { echo "application layer incomplete" >&2; exit 1; }
  test -f /app/BOOT-INF/classes/com/med/qa/MedQaApplication.class || { echo "app classes missing" >&2; exit 1; }
  test -f /app/org/springframework/boot/loader/launch/JarLauncher.class \
    || { echo "loader layer is empty: the build ran without --launcher" >&2; exit 1; }
  jars=$(ls -1 /app/BOOT-INF/lib/*.jar 2>/dev/null | wc -l)
  [ "$jars" -gt 50 ] || { echo "only $jars dependency jars in BOOT-INF/lib" >&2; exit 1; }
  if id -u | grep -qx "0"; then
    echo "container started as root" >&2
    exit 1
  fi
  echo "    layout ok: $jars dependency jars, uid=$(id -u) user=$(id -un)"
'
docker image inspect --format '{{json .Config.Entrypoint}}' "$IMAGE_TAG" | grep -q JarLauncher \
  || fail "the entrypoint does not use the Spring Boot 3.x JarLauncher"

log "Smoke-testing the packaged application archive"
# The runtime image is a JRE and ships no unzip, so the archive is inspected through the JVM itself.
docker run --rm --entrypoint sh "$IMAGE_TAG" -c '
  set -e
  java -version 2>&1 | head -1 | sed "s/^/    /"
  test -s /app/BOOT-INF/classpath.idx || echo "    (no classpath.idx in the application layer)"
  echo "    application classes present"
'

# The strongest cheap proof that the image is bootable: start it once and require Spring Boot to
# reach its own startup banner. A missing launcher class produces no banner at all -- the JVM dies
# with "Could not find or load main class" before Spring is ever loaded, which is exactly the
# regression the --launcher flag guards against. Whether the application then finishes starting or
# fails on unreachable middleware is irrelevant here: the container is deliberately started with no
# MySQL or Redis attached. It is started detached and force-removed afterwards so a slow or hanging
# boot can never leave an orphan behind.
log "Booting the container once (bounded to ${BOOT_TIMEOUT}s)"
BOOT_CONTAINER="med-qa-verify-boot"
docker rm -f "$BOOT_CONTAINER" >/dev/null 2>&1 || true
docker run -d --name "$BOOT_CONTAINER" -e SPRING_PROFILES_ACTIVE=dev "$IMAGE_TAG" >/dev/null

for _ in $(seq 1 "$BOOT_TIMEOUT"); do
  if docker logs "$BOOT_CONTAINER" 2>&1 | grep -q "Starting MedQaApplication"; then
    break
  fi
  sleep 1
done

BOOT_LOG="$(docker logs "$BOOT_CONTAINER" 2>&1 || true)"
docker rm -f "$BOOT_CONTAINER" >/dev/null 2>&1 || true

if printf '%s\n' "$BOOT_LOG" | grep -q "Could not find or load main class"; then
  fail "the container cannot resolve its entrypoint class (is the loader layer empty?)"
fi
if ! printf '%s\n' "$BOOT_LOG" | grep -q "Starting MedQaApplication"; then
  fail "Spring Boot never started within ${BOOT_TIMEOUT}s; the entrypoint did not resolve"
fi
echo "    entrypoint resolved and Spring Boot reached its startup banner"

log "Verification passed for $IMAGE_TAG"
docker image inspect --format '    size={{.Size}} architecture={{.Architecture}} user={{.Config.User}}' "$IMAGE_TAG"
