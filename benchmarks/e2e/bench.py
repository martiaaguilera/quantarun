#!/usr/bin/env python3
"""End-to-end benchmark against a running QuantaRun stack.

Submits a workload through the public API, waits until every job is final, then reads the timings from PostgreSQL,
where they were written by the database clock (created, assigned, started, finished), so the driver's own latency
does not distort them. Prints one JSON document with the environment metadata the brief requires.

Scenarios:
  burst   N jobs submitted as fast as possible: throughput and queue wait under a backlog.
  steady  jobs at a fixed rate below capacity: the latency a single job sees on an idle-ish system.

Usage:
  python3 benchmarks/e2e/bench.py burst  --jobs 1000 --duration-ms 100
  python3 benchmarks/e2e/bench.py steady --rate 5 --seconds 60 --duration-ms 200

Needs the stack on --url (default http://localhost:8080) and the compose PostgreSQL container for the timing query.
"""

import argparse
import concurrent.futures
import json
import os
import platform
import subprocess
import sys
import time
import urllib.request

ADMIN = os.environ.get("QUANTARUN_ADMIN_TOKEN", "local-dev-admin-token-change-me-0123456789")


def call(url, method, path, token, body=None):
    request = urllib.request.Request(
        url + path,
        method=method,
        data=None if body is None else json.dumps(body).encode(),
        headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"},
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        raw = response.read()
        return json.loads(raw) if raw else None


def psql(container, sql):
    return subprocess.run(
        ["docker", "exec", container, "psql", "-U", "quantarun", "-d", "quantarun", "-AtF", "|", "-c", sql],
        check=True, capture_output=True, text=True,
    ).stdout.strip()


def project_with_key(url):
    project = call(url, "POST", "/api/v1/projects", ADMIN, {"name": "bench-%d" % int(time.time() * 1000)})
    key = call(url, "POST", "/api/v1/projects/%s/api-keys" % project["id"], ADMIN, {"label": "bench"})
    return project["id"], key["secret"]


def job(duration_ms):
    return {
        "workloadType": "delay",
        "payload": {"durationMs": duration_ms},
        "maxAttempts": 3,
        "resources": {"cpuMillis": 250, "memoryMib": 128},
    }


def submit_burst(url, key, jobs, duration_ms, threads):
    with concurrent.futures.ThreadPoolExecutor(threads) as pool:
        list(pool.map(lambda _: call(url, "POST", "/api/v1/jobs", key, job(duration_ms)), range(jobs)))


def submit_steady(url, key, rate, seconds, duration_ms):
    interval = 1.0 / rate
    start = time.monotonic()
    count = int(rate * seconds)
    with concurrent.futures.ThreadPoolExecutor(8) as pool:
        for i in range(count):
            delay = start + i * interval - time.monotonic()
            if delay > 0:
                time.sleep(delay)
            pool.submit(call, url, "POST", "/api/v1/jobs", key, job(duration_ms))
    return count


def wait_until_final(container, project, timeout):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        left = psql(container, "SELECT count(*) FROM jobs WHERE project_id = '%s' AND status NOT IN "
                               "('SUCCEEDED','FAILED','DEAD','CANCELLED')" % project)
        if left == "0":
            return True
        time.sleep(0.5)
    return False


def timings(container, project):
    # First attempts only: retries would mix backoff into the queue wait.
    row = psql(container, """
        WITH a AS (
            SELECT j.created_at, j.finished_at AS job_finished, a.assigned_at, a.started_at
            FROM jobs j JOIN job_attempts a ON a.job_id = j.id AND a.attempt_no = 1
            WHERE j.project_id = '%s')
        SELECT count(*),
               extract(epoch FROM max(job_finished) - min(created_at)),
               %s, %s, %s
        FROM a""" % (project,
                     pct("assigned_at - created_at"),
                     pct("started_at - assigned_at"),
                     pct("started_at - created_at")))
    values = [float(v) for v in row.split("|")]
    names = ["placement", "claim", "time_to_start"]
    out = {"jobs": int(values[0]), "makespan_s": round(values[1], 2)}
    for i, name in enumerate(names):
        p50, p95, p99, mx = values[2 + 4 * i: 6 + 4 * i]
        out[name + "_ms"] = {"p50": round(p50 * 1000), "p95": round(p95 * 1000), "p99": round(p99 * 1000),
                             "max": round(mx * 1000)}
    out["throughput_jobs_per_s"] = round(out["jobs"] / out["makespan_s"], 1)
    out["retries"] = int(psql(container, "SELECT count(*) FROM job_attempts a JOIN jobs j ON j.id = a.job_id "
                                         "WHERE j.project_id = '%s' AND a.attempt_no > 1" % project))
    return out


def pct(expression):
    parts = ["percentile_cont(%s) WITHIN GROUP (ORDER BY extract(epoch FROM %s))" % (p, expression)
             for p in ("0.5", "0.95", "0.99")]
    parts.append("extract(epoch FROM max(%s))" % expression)
    return ", ".join(parts)


def scheduler_cycles(url):
    """Count and mean of placing cycles, from the Prometheus timer (cumulative since start; diffed by the caller)."""
    with urllib.request.urlopen(url + "/actuator/prometheus", timeout=10) as response:
        text = response.read().decode()
    count = total = 0.0
    for line in text.splitlines():
        if line.startswith("quantarun_scheduler_cycle_seconds_count") and 'result="placed"' in line:
            count += float(line.rsplit(" ", 1)[1])
        if line.startswith("quantarun_scheduler_cycle_seconds_sum") and 'result="placed"' in line:
            total += float(line.rsplit(" ", 1)[1])
    return count, total


def environment(commit_override):
    commit = commit_override or subprocess.run(["git", "rev-parse", "--short", "HEAD"],
                                               capture_output=True, text=True).stdout.strip()
    dirty = "" if commit_override else subprocess.run(["git", "status", "--porcelain", "--untracked-files=no"],
                                                      capture_output=True, text=True).stdout.strip()
    cpu = next((line.split(":", 1)[1].strip() for line in open("/proc/cpuinfo") if line.startswith("model name")), "?")
    memory_gb = round(int(next(line.split()[1] for line in open("/proc/meminfo")
                               if line.startswith("MemTotal"))) / 1024 / 1024)
    return {
        "date": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "commit": commit + ("-dirty" if dirty else ""),
        "hardware": "%d vCPU %s, %d GB RAM" % (os.cpu_count(), cpu, memory_gb),
        "os": "%s %s" % (platform.system(), platform.release()),
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("scenario", choices=["burst", "steady"])
    parser.add_argument("--url", default="http://localhost:8080")
    parser.add_argument("--container", default="quantarun-postgres-1")
    parser.add_argument("--jobs", type=int, default=1000)
    parser.add_argument("--rate", type=float, default=5)
    parser.add_argument("--seconds", type=float, default=60)
    parser.add_argument("--duration-ms", type=int, default=100)
    parser.add_argument("--threads", type=int, default=16)
    parser.add_argument("--label", default="")
    parser.add_argument("--commit", default="", help="the commit the running stack was built from, if not HEAD")
    args = parser.parse_args()

    project, key = project_with_key(args.url)
    cycles_before = scheduler_cycles(args.url)
    started = time.monotonic()
    if args.scenario == "burst":
        submit_burst(args.url, key, args.jobs, args.duration_ms, args.threads)
        submitted = args.jobs
    else:
        submitted = submit_steady(args.url, key, args.rate, args.seconds, args.duration_ms)
    submit_seconds = time.monotonic() - started
    if not wait_until_final(args.container, project, timeout=900):
        sys.exit("jobs did not finish within 15 minutes")
    cycles_after = scheduler_cycles(args.url)
    placing = cycles_after[0] - cycles_before[0]

    result = {
        "label": args.label,
        "scenario": args.scenario,
        "configuration": {"jobs": submitted, "duration_ms": args.duration_ms, "rate": args.rate
                          if args.scenario == "steady" else None, "submit_threads": args.threads},
        "environment": environment(args.commit),
        "submit_seconds": round(submit_seconds, 2),
        "results": timings(args.container, project),
        "placing_cycles": int(placing),
        "placing_cycle_mean_ms": round((cycles_after[1] - cycles_before[1]) / placing * 1000, 1) if placing else None,
    }
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
