#!/usr/bin/env bash
# Stops PostgreSQL for OUTAGE readiness polls while 30 jobs of 8 s run, restarts it, and reports how the system
# recovered: readiness, time until every job is final, lost attempts, duplicate successes and reservation drift.
# Expects the stack on localhost:8080 with the compose PostgreSQL container (quantarun-postgres-1).
#
#   OUTAGE=25 scripts/pg-outage.sh
set -uo pipefail
CP=http://localhost:8080; A="Authorization: Bearer ${QUANTARUN_ADMIN_TOKEN:-local-dev-admin-token-change-me-0123456789}"; J='Content-Type: application/json'
OUTAGE=${OUTAGE:-25}
P=$(curl -sf -X POST $CP/api/v1/projects -H "$A" -H "$J" -d "{\"name\":\"pg-restart-$(date +%s)\"}" | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')
K=$(curl -sf -X POST $CP/api/v1/projects/$P/api-keys -H "$A" -H "$J" -d '{"label":"pg"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["secret"])')
for i in $(seq 1 30); do curl -s -o /dev/null -X POST $CP/api/v1/jobs -H "Authorization: Bearer $K" -H "$J" -d '{"workloadType":"delay","payload":{"durationMs":8000},"maxAttempts":3,"resources":{"cpuMillis":500,"memoryMib":256}}'; done
sleep 4
psql() { docker exec quantarun-postgres-1 psql -U quantarun -d quantarun -Atc "$1"; }
echo "before: $(psql "select status, count(*) from jobs where project_id='$P' group by 1 order by 1" | tr '\n' ' ')"
T0=$(date +%s.%N); docker stop -t 0 quantarun-postgres-1 >/dev/null; echo "postgres stopped"
for i in $(seq 1 $OUTAGE); do T=$(date +%s.%N); printf "%s " "$(curl -s -m 5 -o /dev/null -w '%{http_code}' $CP/actuator/health/readiness)"; python3 -c "import time;time.sleep(max(0,1-(time.time()-$T)))"; done; echo
docker start quantarun-postgres-1 >/dev/null; T1=$(date +%s.%N); echo "postgres started after $(python3 -c "print(round($T1-$T0,1))") s"
until curl -sf $CP/actuator/health/readiness >/dev/null; do sleep 0.2; done
echo "control plane ready $(python3 -c "import time;print(round(time.time()-$T1,1))") s after postgres started"
for i in $(seq 1 120); do
  n=$(psql "select count(*) from jobs where project_id='$P' and status not in ('SUCCEEDED','FAILED','DEAD','CANCELLED')" 2>/dev/null)
  [ "$n" = "0" ] && break; sleep 1; done
echo "all jobs final $(python3 -c "import time;print(round(time.time()-$T1,1))") s after postgres started"
echo "jobs: $(psql "select status, count(*) from jobs where project_id='$P' group by 1" | tr '\n' ' ')"
echo "attempts: $(psql "select a.status, count(*) from job_attempts a join jobs j on j.id=a.job_id where j.project_id='$P' group by 1" | tr '\n' ' ')"
echo "successes per job > 1: $(psql "select count(*) from (select job_id from job_attempts a join jobs j on j.id=a.job_id where j.project_id='$P' and a.status='SUCCEEDED' group by 1 having count(*)>1) x")"
echo "reservation mismatches: $(psql "select count(*) from workers w left join (select worker_id, count(*) s from job_attempts where status in ('ASSIGNED','RUNNING') group by 1) a on a.worker_id=w.id where w.slots_reserved <> coalesce(a.s,0)")"
echo "workers: $(psql "select name, lifecycle from workers where lifecycle <> 'DEREGISTERED' order by registered_at desc limit 6" | tr '\n' ' ')"
