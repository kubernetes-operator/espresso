#!/bin/bash
# K8s 배포용 이미지 빌드 (+ --push 시 레지스트리에 올림)
#   deploy/images.sh            → 빌드만
#   deploy/images.sh --push     → 빌드 후 push (레지스트리 쓰기: 실행 전 승인 필요)
# 태그: git 커밋 앞 7자리 (변경 없는 상태에서 실행 권장)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REG="${REGISTRY:-registry.local.cloud:5000}"
REPO="${IMAGE_REPO:-cafedelyon}"
TAG="${TAG:-$(git -C "$ROOT" rev-parse --short=7 HEAD)}"
if [ -n "$(git -C "$ROOT" status --porcelain)" ]; then echo "경고: 커밋되지 않은 변경이 있습니다 (태그 $TAG 와 내용이 다를 수 있음)"; fi
"$ROOT/deploy/build.sh"
for svc in auth core settlement; do
  docker build -q -t "$REG/$REPO/$svc:$TAG" --build-arg JAR=$svc-service.jar \
    -f "$ROOT/deploy/compose/Dockerfile.service" "$ROOT/backend/$svc-service/build/libs"
done
docker build -q -t "$REG/$REPO/web:$TAG" -f "$ROOT/deploy/docker/Dockerfile.web" "$ROOT"
echo "이미지 태그: $TAG"
if [ "${1:-}" = "--push" ]; then
  for img in auth core settlement web; do docker push -q "$REG/$REPO/$img:$TAG"; done
  echo "push 완료: $REG/$REPO/{auth,core,settlement,web}:$TAG"
fi
