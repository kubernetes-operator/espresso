#!/bin/bash
# standby: 데이터 디렉터리가 비어 있으면 primary 에서 base backup 후 streaming replication 으로 기동
set -e
if [ ! -s "$PGDATA/PG_VERSION" ]; then
  until pg_isready -h pg-primary -U postgres; do echo "waiting primary"; sleep 2; done
  sleep 3
  pg_basebackup -h pg-primary -U replicator -D "$PGDATA" -X stream -S standby1 -R -P
  chmod 700 "$PGDATA"
fi
exec postgres -c hot_standby=on -c primary_slot_name=standby1
