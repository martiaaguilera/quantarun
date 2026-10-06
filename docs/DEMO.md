# Five-minute demo

A worker is killed while it runs a job. The job finishes elsewhere, from its last checkpoint, and the console shows
why each step happened. Then the same kind of workload is replayed under two policies to compare them.

The outputs below are from a rehearsal on 2026-10-06 (Phase 15 branch). Ids and timings will differ on your
machine; the shape will not. Commands need `curl` and `jq`, and run against the web entry point (nginx), as the
console does.

## 0. Start the stack (before the audience arrives)

```bash
cp .env.example .env
docker compose up --build --wait
```

Open the console at <http://localhost:3000> and sign in with the operator token from `.env`
(`QUANTARUN_ADMIN_TOKEN`). Keep the **Overview** page visible; it updates live.

```bash
ADMIN="Authorization: Bearer local-dev-admin-token-change-me-0123456789"
API=http://localhost:3000/api/v1
```

## 1. Three different workers (30 s)

```bash
curl -s $API/workers -H "$ADMIN" | jq -r '.[] | select(.lifecycle == "ACTIVE") | "\(.name)  \(.labels)  \(.capacity)"'
```

```
worker-accel  ["cuda","large-model"]  {"cpuMillis":8000,"memoryMib":32768,"accelerators":2,"slots":2}
worker-cpu    []                      {"cpuMillis":4000,"memoryMib":8192,"accelerators":0,"slots":4}
worker-mixed  ["cuda"]                {"cpuMillis":8000,"memoryMib":16384,"accelerators":1,"slots":4}
```

Each worker is its own process, with no database access: it registers, heartbeats, claims work and reports over
HTTP (ADR-0005). Accelerators are simulated slots. The **Workers** page shows the same, with live reservations.

## 2. A tenant and a mixed workload (45 s)

```bash
PROJECT=$(curl -s -X POST $API/projects -H "$ADMIN" -H 'Content-Type: application/json' -d '{"name":"demo"}' | jq -r .id)
KEY=$(curl -s -X POST $API/projects/$PROJECT/api-keys -H "$ADMIN" -H 'Content-Type: application/json' -d '{"label":"demo"}' | jq -r .secret)
AUTH="Authorization: Bearer $KEY"
submit() { curl -s -X POST $API/jobs -H "$AUTH" -H 'Content-Type: application/json' -d "$1" | jq -r .id; }

# Six 15 s CPU jobs, four accelerator inference jobs, one job that fails once with a transient error
for i in $(seq 6); do submit '{"workloadType":"delay","payload":{"durationMs":15000},"resources":{"cpuMillis":1000,"memoryMib":1024}}'; done
for i in $(seq 4); do submit '{"workloadType":"mock-inference","payload":{"inputTokens":2000,"outputTokens":400,"latencyMs":8000},"priority":7,"resources":{"cpuMillis":2000,"memoryMib":4096,"accelerators":1},"requiredLabels":["cuda"]}'; done
submit '{"workloadType":"fail","payload":{"failureClass":"TRANSIENT","message":"upstream reset the connection","succeedOnAttempt":2},"maxAttempts":3,"resources":{"cpuMillis":250,"memoryMib":128}}'

# The star: a three-stage job that commits a checkpoint after each stage
STAGED=$(submit '{"workloadType":"staged","payload":{"stages":[{"name":"tokenize","durationMs":4000},{"name":"embed","durationMs":6000},{"name":"index","durationMs":4000}]},"maxAttempts":3,"resources":{"cpuMillis":1000,"memoryMib":2048},"requiredLabels":["cuda"]}')
```

The key is shown once and stored only as a hash. Submissions with an `Idempotency-Key` header are safe to retry.

## 3. Why the scheduler put it there (45 s)

```bash
curl -s $API/jobs/$STAGED/decisions -H "$AUTH" | jq '.[0] | {policy, reason, candidates: [.candidates[] | {workerName, verdict, detail}]}'
```

```json
{
  "policy": "BIN_PACKING",
  "reason": "Placed on worker-mixed by BIN_PACKING (fits; 100% utilised after placement)",
  "candidates": [
    { "workerName": "worker-mixed", "verdict": "CHOSEN", "detail": "fits; 100% utilised after placement" },
    { "workerName": "worker-cpu", "verdict": "MISSING_LABELS", "detail": "missing labels [cuda]" },
    { "workerName": "worker-accel", "verdict": "INSUFFICIENT_FREE_CAPACITY", "detail": "needs a slot, 0 free" }
  ]
}
```

Every worker gets a verdict, not just the winner. The **Scheduler** page lists every decision, including jobs that
are waiting and why.

## 4. Kill the worker running it (15 s)

Wait until the job has committed its first checkpoint (a few seconds; the job page shows it), then find its worker
and kill it. `kill` is SIGKILL: no deregistration, no final report.

```bash
WORKER=$(curl -s $API/jobs/$STAGED/attempts -H "$AUTH" | jq -r '.[-1].workerId')
NAME=$(curl -s $API/workers/$WORKER -H "$ADMIN" | jq -r .name)
docker compose kill $NAME
```

Open the job in the console (**Jobs**, then the staged job) and leave it on screen.

## 5. Lease expiry and recovery (30 s, mostly waiting)

Nothing happens for about 15 s. The control plane does not know the worker is dead; it knows only that the worker
stopped renewing its lease. Then:

```bash
curl -s $API/jobs/$STAGED/events -H "$AUTH" | jq -r '.[] | "\(.occurredAt[11:23])  \(.type)  \(.details.resume // .details.reason // .details.message // "")"'
```

```
14:11:41.497  SUBMITTED
14:11:41.511  SCHEDULED             Placed on worker-mixed by BIN_PACKING (fits; 100% utilised after placement)
14:11:41.525  STARTED               from zero
14:11:45.577  CHECKPOINT_COMMITTED
14:11:51.610  CHECKPOINT_COMMITTED
14:12:06.908  ATTEMPT_LOST          lease expired
14:12:06.908  RETRY_SCHEDULED
14:12:06.933  SCHEDULED             Placed on worker-accel by BIN_PACKING (fits; 100% utilised after placement)
14:12:06.951  STARTED               after stage 1
14:12:10.980  CHECKPOINT_COMMITTED
14:12:11.001  SUCCEEDED
```

The worker was killed at 14:11:53.3. Its lease ran out 13.6 s later. The job was placed elsewhere 25 ms after that,
resumed after stage 1 instead of from zero, and succeeded. The killed worker's reservation was released in the same
transaction that marked the attempt lost.

## 6. The job's story in the console (45 s)

![Job page](images/job.png)

The lifeline shows attempt 1 on worker-mixed with its two checkpoints and the lease expiry, then attempt 2 on
worker-accel. Below it: every step with its reason, both attempts, the decision with each candidate's verdict, and
the checkpoints with the attempt that committed each. **Open trace** opens the same job as one OpenTelemetry trace in
Jaeger, if the observability overlay is running.

Check the rest of the batch:

```bash
curl -s "$API/jobs?limit=50" -H "$AUTH" | jq -r '.items[] | "\(.workloadType) \(.status) attempts=\(.attemptCount)"' | sort | uniq -c
```

```
      6 delay SUCCEEDED attempts=1
      1 fail SUCCEEDED attempts=2
      3 mock-inference SUCCEEDED attempts=1
      1 mock-inference SUCCEEDED attempts=2
      1 staged SUCCEEDED attempts=2
```

Every job succeeded exactly once. The other job that was running on the killed worker took two attempts too, and
the transient failure was retried with backoff. Bring the worker back; it registers as a new member, and the old
registration stays in history as OFFLINE:

```bash
docker compose start $NAME
```

## 7. Replay the same workload under different policies (60 s)

The policy lab replays a generated workload through the scheduler's own placement code, in simulated time. In the
console: **Policy lab**, scenario *Noisy neighbor*, seed 42, run. Or through the API (a project key may run it):

```bash
curl -s -X POST $API/simulations -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"scenario":"NOISY_NEIGHBOR","seed":42,"policies":["FIFO","FAIR_SHARE"]}' \
  | jq -r '.results.policies[] | "\(.policy)  fairness=\(.metrics.fairness*1000|round/1000)  " + (.metrics.projects | map("\(.project) p50 wait \(.queueWaitMs.p50/1000|round)s") | join(", "))'
```

```
FIFO        fairness=0.462  tenant-a p50 wait 624s, tenant-b p50 wait 527s, tenant-c p50 wait 531s
FAIR_SHARE  fairness=0.991  tenant-a p50 wait 758s, tenant-b p50 wait 1s, tenant-c p50 wait 1s
```

One tenant floods the queue with 1,600 jobs while two others submit 200 each. Under FIFO everyone waits behind the
flood. Under FAIR_SHARE the light tenants wait about a second, and the flooding tenant pays for it: 758 s instead of
624 s at the median. Throughput and utilisation are the same under both; only who waits changes.

![Policy lab](images/policy-lab.png)

## 8. Same seed, same answer (15 s)

Run the same request again. The result hashes are identical, because the planner is pure (no clock, no randomness,
no SQL) and the simulator seeds everything (ADR-0004):

```
FIFO        5fdca1b58d6deca43fcc3a47446bdbe02433ee1f284e152aa0d3225fa4654bca
FAIR_SHARE  101ee58cb1a1f6be8fa8114e2dc41bc3ccfce55b9c850bcb77952cf6af04005a
```

## If there is time

- **Chaos lab:** start the stack with `QUANTARUN_CHAOS_ENABLED=true` and aim *Kill worker*, *Pause heartbeat* or a
  provider fault at a worker. The experiment page shows the recovery timeline and, per job, when the disruption was
  detected and when the job recovered (CHAOS.md).
- **Traces and metrics:** `docker compose -f docker-compose.yml -f docker-compose.observability.yml up --build --wait`,
  then Jaeger on <http://localhost:16686> and Prometheus on <http://localhost:9090> (OBSERVABILITY.md).
