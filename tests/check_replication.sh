#!/bin/bash
# DB 2중화 확인: primary 의 복제 상태 + standby 가 읽기 전용 복제본으로 최신 데이터를 갖는지
set -e
PJ="${COMPOSE_PROJECT_NAME:-espresso}"   # CI 등 다른 프로젝트 이름으로 띄운 경우
P="docker exec ${PJ}-pg-primary-1 psql -U postgres -tAc"
S="docker exec ${PJ}-pg-standby-1 psql -U postgres -tAc"
echo "[primary] 복제 연결:"; $P "select application_name, state, sync_state, replay_lag from pg_stat_replication"
echo "[standby] 복구(읽기 전용) 모드: $($S 'select pg_is_in_recovery()')"
for db in auth_db core_db settlement_db; do
  echo "[$db] primary/standby 행 수 비교:"
  case $db in auth_db) q="select count(*) from auth.app_user";; core_db) q="select count(*) from logistics.request";; settlement_db) q="select count(*) from stl.journal_entry";; esac
  sleep 1; echo "  primary=$(docker exec ${PJ}-pg-primary-1 psql -U postgres -d $db -tAc "$q")  standby=$(docker exec ${PJ}-pg-standby-1 psql -U postgres -d $db -tAc "$q")"
done
echo "[standby] 쓰기 차단 확인:"; docker exec ${PJ}-pg-standby-1 psql -U postgres -d core_db -c "create table t(x int)" 2>&1 | tail -1
