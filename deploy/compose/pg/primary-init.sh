#!/bin/bash
# primary 최초 기동 시 1회: DB 3개 생성 + 복제 계정/슬롯 + 복제 접속 허용
set -e
psql -v ON_ERROR_STOP=1 -U postgres <<SQL
CREATE DATABASE auth_db;
CREATE DATABASE core_db;
CREATE DATABASE settlement_db;
CREATE ROLE replicator WITH REPLICATION LOGIN;
SELECT pg_create_physical_replication_slot('standby1');
SQL
# 로컬 데모 네트워크 전용 (운영: CloudNativePG 가 인증서/Secret 기반으로 구성)
echo "host replication replicator all trust" >> "$PGDATA/pg_hba.conf"
