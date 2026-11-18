#!/bin/bash
# 백엔드 JAR + 웹 정적 파일 빌드 (로컬에 Java/Node 가 없어도 Docker 로 빌드)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$ROOT/.cache/gradle" "$ROOT/.cache/npm"
docker run --rm -u "$(id -u):$(id -g)" -e GRADLE_USER_HOME=/cache \
  -v "$ROOT/.cache/gradle:/cache" -v "$ROOT:/ws" -w /ws/backend \
  gradle:8.10.2-jdk21 gradle --no-daemon -q build -x test
docker run --rm -u "$(id -u):$(id -g)" -e npm_config_cache=/npmcache -e HOME=/tmp \
  -v "$ROOT/.cache/npm:/npmcache" -v "$ROOT/web:/web" -w /web \
  node:22-alpine sh -c "npm ci --no-audit --no-fund || npm install --no-audit --no-fund; npm run build"
echo "build done"
