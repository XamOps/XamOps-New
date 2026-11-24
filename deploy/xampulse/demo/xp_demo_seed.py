#!/usr/bin/env python3
"""XamPulse DEMO data seeder (local development only).

Writes 24 h of GENERATED sample telemetry for six fake hosts into the LOCAL XamPulse stack, through the same OTLP
gateway path real agents use (logs + metrics) plus a direct insert for platform events. It is OFF unless you opt in:

  * XAMPULSE_DEMO_ENABLED=true must be set in the environment (same switch name the backend guard uses), and
  * the gateway / ClickHouse URLs must be loopback addresses (127.0.0.1, localhost, ::1). Remote targets are refused.

Demo contract (docs: impl-plan section 7). Every generated row is unmistakable:
  * instance ids start with "i-demo", host names start with "demo-";
  * logs carry attrs['xp.demo']='true' (gateway stamps it from X-Xp-Demo); metrics carry label xp.demo='true';
  * events have a "[DEMO]" title prefix and an "i-demo" resource.
`purge` deletes exactly those rows for one tenant (parameterised, tenant-scoped).

Standard library only. Deterministic: the same --seed and --now give byte-identical output.
"""
from __future__ import annotations

import argparse
import base64
import datetime as dt
import hashlib
import ipaddress
import json
import math
import os
import random
import sys
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Tuple

DEMO_SEED = 42
DEMO_REGION = "us-east-1"
DEMO_INSTANCE_PREFIX = "i-demo"
DEMO_HOST_PREFIX = "demo-"
DEMO_EVENT_PREFIX = "[DEMO] "
DEMO_FLAG_ENV = "XAMPULSE_DEMO_ENABLED"
RETAIN_DAYS = 3

# name -> role. Instance id is derived: demo-web-1 -> i-demo-web-1.
HOSTS: List[Tuple[str, str]] = [
    ("demo-web-1", "web"), ("demo-web-2", "web"), ("demo-web-3", "web"),
    ("demo-api-1", "api"), ("demo-api-2", "api"), ("demo-db-1", "db"),
]


def instance_id(host: str) -> str:
    return DEMO_INSTANCE_PREFIX + "-" + host[len(DEMO_HOST_PREFIX):]


class DemoRefused(Exception):
    """Raised when the demo switch or the loopback-only rule is not satisfied."""


def assert_demo_allowed(env: Dict[str, str], urls: List[str]) -> None:
    if env.get(DEMO_FLAG_ENV, "").strip().lower() != "true":
        raise DemoRefused(f"demo seeding is OFF. Set {DEMO_FLAG_ENV}=true to opt in (local development only).")
    for url in urls:
        host = urllib.parse.urlparse(url).hostname or ""
        if not _is_loopback(host):
            raise DemoRefused(f"refusing to write demo data to non-local host '{host}'. Demo data is local-only.")


def _is_loopback(host: str) -> bool:
    if host.lower() == "localhost":
        return True
    try:
        return ipaddress.ip_address(host).is_loopback
    except ValueError:
        return False


# --------------------------------------------------------------------------------------------------------------------
# Data model
# --------------------------------------------------------------------------------------------------------------------
@dataclass
class MetricPoint:
    """One datapoint, shaped like the agent's collector sends it (hostmetrics / docker_stats, contrib v0.164.1).

    kind "gauge" is an OTel Gauge; "sum" is a cumulative, monotonic Sum (byte counters), with start_ns the time the
    counter last started from zero. resource holds extra RESOURCE attributes: container identity travels there,
    never in the datapoint labels, exactly as docker_stats sends it.
    """
    name: str
    labels: Dict[str, str]
    ts_ns: int
    value: float
    kind: str = "gauge"
    resource: Dict[str, str] = field(default_factory=dict)
    start_ns: int = 0


@dataclass
class LogLine:
    service: str
    source: str
    ts_ns: int
    severity: str  # INFO / WARN / ERROR / FATAL
    body: str


@dataclass
class Event:
    ts: dt.datetime
    kind: str
    resource: str
    title: str
    detail: str


@dataclass
class Dataset:
    metrics: Dict[str, List[MetricPoint]] = field(default_factory=dict)
    logs: Dict[str, List[LogLine]] = field(default_factory=dict)
    events: List[Event] = field(default_factory=list)

    def counts(self) -> Dict[str, int]:
        return {"hosts": len(self.metrics), "metric_points": sum(len(v) for v in self.metrics.values()),
                "log_lines": sum(len(v) for v in self.logs.values()), "events": len(self.events)}


SEVERITY_NUMBER = {"INFO": 9, "WARN": 13, "ERROR": 17, "FATAL": 21}


def _ns(t: dt.datetime) -> int:
    return int(t.timestamp()) * 1_000_000_000 + t.microsecond * 1000


def floor_minute(t: dt.datetime) -> dt.datetime:
    return t.replace(second=0, microsecond=0)


def _clamp(v: float, lo: float = 0.0, hi: float = 1.0) -> float:
    return max(lo, min(hi, v))


# Network interfaces and block devices per role, as hostmetrics reports them on an EC2 host. lo and docker0 carry
# traffic that never leaves the host and nvme0n1p1 repeats its disk's bytes, so the host charts must leave them out.
NICS = {"web": ("ens5", "lo", "docker0"), "api": ("ens5", "lo", "docker0"), "db": ("ens5", "lo")}
DISKS = ("nvme0n1", "nvme0n1p1")
# One container per role (db runs bare Postgres): name, image, memory limit in bytes.
CONTAINERS = {"web": ("nginx", "nginx:1.27", 512 * 1024 ** 2), "api": ("api", "demo/api:2026.10.31-3", 2 * 1024 ** 3)}


def container_id(host: str, name: str) -> str:
    """Deterministic 64-hex id, the shape Docker uses."""
    return hashlib.sha256(f"{host}/{name}".encode()).hexdigest()


# --------------------------------------------------------------------------------------------------------------------
# Generator
# --------------------------------------------------------------------------------------------------------------------
def build_dataset(now: dt.datetime, hours: int = 24, step_s: int = 60, seed: int = DEMO_SEED) -> Dataset:
    """Generate the whole demo dataset. `now` must be timezone-aware UTC."""
    if now.tzinfo is None:
        raise ValueError("now must be timezone-aware (UTC)")
    now = floor_minute(now.astimezone(dt.timezone.utc))
    rng = random.Random(seed)
    ds = Dataset()
    start = now - dt.timedelta(hours=hours)
    steps = int(hours * 3600 / step_s)

    steal_from, steal_to = now - dt.timedelta(hours=3), now - dt.timedelta(hours=2)
    oom_from, oom_to = now - dt.timedelta(minutes=90), now - dt.timedelta(minutes=80)
    new_err_from = now - dt.timedelta(minutes=25)
    # demo-web-2 is stopped for 10 minutes (the EC2_STATE events below): no telemetry at all, then every counter
    # starts again from zero, the way a real reboot looks to the collector.
    stop_from, stop_to = now - dt.timedelta(hours=10), now - dt.timedelta(hours=10) + dt.timedelta(minutes=10)

    for hidx, (host, role) in enumerate(HOSTS):
        metrics: List[MetricPoint] = []
        logs: List[LogLine] = []
        demo_label = {"xp.demo": "true", "host.name": host}
        base_cpu = {"web": 0.22, "api": 0.30, "db": 0.18}[role]
        base_mem = {"web": 0.45, "api": 0.55, "db": 0.68}[role]
        counters: Dict[Tuple[str, ...], float] = {}
        counter_start = _ns(start)
        rebooted = False
        container_restarts = 0.0
        for i in range(steps + 1):
            t = start + dt.timedelta(seconds=i * step_s)
            frac = i / steps
            if host == "demo-web-2" and stop_from <= t < stop_to:
                continue
            if host == "demo-web-2" and t >= stop_to and not rebooted:
                counters.clear()
                counter_start = _ns(stop_to)
                rebooted = True
            hour = t.hour + t.minute / 60
            diurnal = 0.5 + 0.5 * math.sin((hour - 9) / 24 * 2 * math.pi)  # busiest around 15:00 UTC
            user = _clamp(base_cpu * (0.6 + 0.8 * diurnal) + rng.gauss(0, 0.015), 0.01, 0.9)
            system = _clamp(user * 0.25 + rng.gauss(0, 0.005), 0.0, 0.5)
            iowait = _clamp((0.12 if role == "db" else 0.01) + rng.gauss(0, 0.005), 0.0, 0.4)
            steal = _clamp(0.002 + abs(rng.gauss(0, 0.001)), 0.0, 0.3)
            if host == "demo-web-3" and steal_from <= t < steal_to:
                steal = _clamp(0.18 + rng.gauss(0, 0.03), 0.08, 0.35)  # noisy-neighbour episode
                user = _clamp(user + 0.15, 0.0, 0.9)
            idle = _clamp(1.0 - user - system - iowait - steal)
            mem_used = _clamp(base_mem + 0.05 * diurnal + rng.gauss(0, 0.004), 0.05, 0.99)
            if host == "demo-api-2":
                if oom_from - dt.timedelta(minutes=40) <= t < oom_from:
                    mem_used = _clamp(0.62 + (t - (oom_from - dt.timedelta(minutes=40))).total_seconds() / 2400 * 0.35, 0, 0.98)
                elif oom_from <= t < oom_to:
                    mem_used = 0.97
                elif t >= oom_to and t < oom_to + dt.timedelta(minutes=2):
                    mem_used = 0.35  # process restarted after the OOM kill
            mem_cached = _clamp(0.25 * (1 - mem_used) + rng.gauss(0, 0.003), 0.0, 0.5)
            mem_free = _clamp(1.0 - mem_used - mem_cached)
            disk = 0.42 + 0.0 * frac
            if host == "demo-db-1":
                disk = 0.62 + 0.31 * frac  # disk filling: 62% -> 93% over the window
            elif role == "api":
                disk = 0.35 + 0.02 * frac
            disk = _clamp(disk + rng.gauss(0, 0.001))
            cores = 4 if role != "db" else 8
            load1 = max(0.0, (user + system + iowait) * cores + rng.gauss(0, 0.05))
            rx = max(0.0, 1.2e5 * (0.4 + diurnal) * (1.5 if role == "web" else 0.7) + rng.gauss(0, 4e3))
            tx = max(0.0, rx * 1.8 + rng.gauss(0, 4e3))
            ts_ns = _ns(t)

            def add(name: str, value: float, **extra: str) -> None:
                labels = dict(demo_label)
                labels.update(extra)
                metrics.append(MetricPoint(name, labels, ts_ns, round(value, 6)))

            def count(name: str, rate_per_s: float, resource: Optional[Dict[str, str]] = None, **extra: str) -> None:
                """Cumulative counter: adds rate x step and reports the running total (a large lifetime total on a
                long-running host, zero again after the reboot)."""
                key = (name,) + tuple(sorted(extra.items())) + tuple(sorted((resource or {}).items()))
                if key not in counters:
                    counters[key] = 0.0 if rebooted else (1 + hidx) * 7.3e10
                counters[key] += max(0.0, rate_per_s) * step_s
                labels = dict(demo_label)
                labels.update(extra)
                metrics.append(MetricPoint(name, labels, ts_ns, round(counters[key], 1), "sum", dict(resource or {}), counter_start))

            for state, val in (("user", user), ("system", system), ("iowait", iowait), ("steal", steal), ("idle", idle)):
                add("system.cpu.utilization", val, state=state)
            for state, val in (("used", mem_used), ("free", mem_free), ("cached", mem_cached)):
                add("system.memory.utilization", val, state=state)
            add("system.filesystem.utilization", disk, mountpoint="/", device="nvme0n1p1")
            add("system.cpu.load_average.1m", load1)
            # Bytes per second on each interface; only ens5 is traffic in and out of the instance.
            nic_rate = {"ens5": (rx, tx), "lo": (2.0e3, 2.0e3), "docker0": (0.06 * rx, 0.05 * tx)}
            for nic in NICS[role]:
                count("system.network.io", nic_rate[nic][0], direction="receive", device=nic)
                count("system.network.io", nic_rate[nic][1], direction="transmit", device=nic)
            reads = (3.5e6 if role == "db" else 2.0e5) * (0.4 + diurnal)
            writes = (2.8e6 if role == "db" else 1.4e5) * (0.4 + diurnal)
            for dev in DISKS:
                share = 1.0 if dev == "nvme0n1" else 0.999  # the root partition carries almost all of the disk's I/O
                count("system.disk.io", reads * share, direction="read", device=dev)
                count("system.disk.io", writes * share, direction="write", device=dev)

            if role in CONTAINERS:
                cname, image, limit = CONTAINERS[role]
                res = {"container.id": container_id(host, cname), "container.name": cname, "container.image.name": image}
                # docker_stats reports percent of ONE CPU: the api container uses more than a core at peak.
                share = 0.3 if role == "web" else 0.8
                ccpu = max(0.0, (user + system) * cores * 100 * share + rng.gauss(0, 1.0))
                cmem = 6.0e7 + 5.0e6 * diurnal if role == "web" else limit * _clamp(mem_used * 0.95, 0.05, 0.99)
                if host == "demo-api-2" and t >= oom_to and container_restarts == 0:
                    container_restarts = 1.0  # the OOM-killed container was restarted by Docker
                metrics.append(MetricPoint("container.cpu.utilization", dict(demo_label), ts_ns, round(ccpu, 4), "gauge", res))
                metrics.append(MetricPoint("container.memory.usage.total", dict(demo_label), ts_ns, round(cmem, 0), "gauge", res))
                metrics.append(MetricPoint("container.memory.usage.limit", dict(demo_label), ts_ns, float(limit), "gauge", res))
                metrics.append(MetricPoint("container.restarts", dict(demo_label), ts_ns, container_restarts, "sum", res, counter_start))

            _gen_logs(rng, logs, host, role, t, ts_ns, disk, oom_from, oom_to, new_err_from)
        ds.metrics[host] = metrics
        ds.logs[host] = logs

    ds.events = _gen_events(now)
    return ds


def _gen_logs(rng: random.Random, out: List[LogLine], host: str, role: str, t: dt.datetime, ts_ns: int,
              disk: float, oom_from: dt.datetime, oom_to: dt.datetime, new_err_from: dt.datetime) -> None:
    jitter = -int(rng.random() * 40) * 1_000_000_000  # up to 40 s earlier than the tick, deterministic, never in the future

    def line(service: str, source: str, severity: str, body: str, extra_ns: int = 0) -> None:
        out.append(LogLine(service, source, ts_ns + jitter + extra_ns, severity, body))

    if role == "web":
        path = rng.choice(["/", "/api/v1/items", "/login", "/static/app.js", "/health"])
        status = rng.choices([200, 304, 404, 502], weights=[80, 12, 6, 2])[0]
        sev = "ERROR" if status >= 500 else ("WARN" if status >= 400 else "INFO")
        ip = f"10.{rng.randint(0, 3)}.{rng.randint(0, 255)}.{rng.randint(1, 254)}"
        line("nginx", "file:/var/log/nginx/access.log", sev,
             f'{ip} - - "GET {path} HTTP/1.1" {status} {rng.randint(120, 52000)} "-" "Mozilla/5.0"')
        if status == 502:
            line("nginx", "file:/var/log/nginx/error.log", "ERROR",
                 f"upstream prematurely closed connection while reading response header from upstream, upstream: \"http://10.0.2.{rng.randint(10, 20)}:8080/\"", 1_000_000)
    elif role == "api":
        line("api", "journald:api.service", "INFO",
             f"request handled method=GET route=/orders/{rng.randint(1000, 99999)} status=200 duration_ms={rng.randint(8, 240)}")
        if rng.random() < 0.02:
            line("api", "journald:api.service", "WARN", f"slow query detected duration_ms={rng.randint(900, 4000)} table=orders")
        if host == "demo-api-1" and t >= new_err_from:
            if (t.minute % 2) == 0:
                line("api", "journald:api.service", "ERROR",
                     f"payment-gateway timeout after 3000ms (order={rng.randint(10000, 99999)}, attempt=2)", 2_000_000)
    else:
        if t.minute % 5 == 0:
            line("postgres", "file:/var/log/postgresql/postgresql.log", "INFO",
                 f"checkpoint complete: wrote {rng.randint(200, 4000)} buffers ({rng.random() * 3:.1f}%); sync files={rng.randint(5, 30)}")
        if disk > 0.9 and t.minute % 10 == 0:
            line("postgres", "file:/var/log/postgresql/postgresql.log", "WARN",
                 f"data volume at {int(disk * 100)}% capacity; vacuum may fail when full", 3_000_000)

    if t.minute % 15 == 0:
        line("syslog", "file:/var/log/syslog", "INFO", "systemd[1]: Started Daily apt download activities.")
    if rng.random() < 0.01:
        line("auth", "file:/var/log/auth.log", "INFO",
             f"sshd[{rng.randint(1000, 30000)}]: Accepted publickey for ec2-user from 10.0.0.{rng.randint(2, 50)} port {rng.randint(30000, 60000)} ssh2")
    if rng.random() < 0.004:
        line("auth", "file:/var/log/auth.log", "WARN",
             f"sshd[{rng.randint(1000, 30000)}]: Failed password for invalid user admin from 203.0.113.{rng.randint(2, 250)} port {rng.randint(30000, 60000)} ssh2")

    if host == "demo-api-2" and oom_from <= t < oom_to:
        line("syslog", "file:/var/log/syslog", "ERROR",
             f"kernel: Out of memory: Killed process {rng.randint(2000, 4000)} (java) total-vm:7811528kB, anon-rss:3921880kB", 4_000_000)
        line("api", "journald:api.service", "FATAL", "java.lang.OutOfMemoryError: Java heap space", 5_000_000)


def _gen_events(now: dt.datetime) -> List[Event]:
    def ev(delta: dt.timedelta, kind: str, host: str, title: str, detail: str) -> Event:
        return Event(now - delta, kind, instance_id(host), DEMO_EVENT_PREFIX + title, detail)

    # Kinds are the backend's (EventQueryService.KINDS): the UI maps each to an icon and filters by them.
    h, m = (lambda x: dt.timedelta(hours=x)), (lambda x: dt.timedelta(minutes=x))
    return sorted([
        ev(h(6), "DEPLOY", "demo-api-1", "Deployment api 2026.10.31-3 completed", "Generated demo deployment event."),
        ev(h(6), "DEPLOY", "demo-api-2", "Deployment api 2026.10.31-3 completed", "Generated demo deployment event."),
        ev(h(10), "EC2_STATE", "demo-web-2", "Instance stopped", "State change running -> stopped (generated demo event)."),
        ev(h(10) - m(10), "EC2_STATE", "demo-web-2", "Instance running", "State change stopped -> running (generated demo event)."),
        ev(h(3) - m(5), "ALERT", "demo-web-3", "CPU steal above 10% for 5m", "Generated demo alert."),
        ev(h(2) - m(5), "ALERT", "demo-web-3", "Resolved: CPU steal back to normal", "Generated demo alert recovery."),
        ev(m(95), "ALERT", "demo-api-2", "Memory above 95% for 5m", "Generated demo alert."),
        ev(m(80), "ALERT", "demo-api-2", "Container api restarted after an OOM kill", "Generated demo event."),
        ev(m(60), "ALERT", "demo-db-1", "Disk above 90%", "Generated demo alert (still open)."),
    ], key=lambda e: e.ts)

#: The event kinds the backend accepts (EventQueryService.KINDS).
BACKEND_EVENT_KINDS = {"DEPLOY", "EC2_STATE", "ALERT", "ANOMALY", "AGENT", "CONFIG"}


# --------------------------------------------------------------------------------------------------------------------
# OTLP/JSON payloads (same wire shape real agents send)
# --------------------------------------------------------------------------------------------------------------------
def _kv(key: str, value: str) -> Dict:
    return {"key": key, "value": {"stringValue": value}}


def _resource(host: str, service: str, source: Optional[str]) -> Dict:
    attrs = [_kv("service.name", service), _kv("host.name", host), _kv("cloud.region", DEMO_REGION),
             _kv("cloud.provider", "aws")]
    if source:
        attrs.append(_kv("xp.source", source))
    return {"attributes": attrs}


def otlp_metrics_payload(host: str, points: List[MetricPoint]) -> Dict:
    """OTLP/JSON the way the agent's collector sends it: one resource per host plus one per container (identity in
    the resource attributes), gauges as gauges and byte counters as cumulative monotonic sums."""
    by_resource: Dict[Tuple[Tuple[str, str], ...], Dict[Tuple[str, str], List[MetricPoint]]] = {}
    for p in points:
        by_resource.setdefault(tuple(sorted(p.resource.items())), {}).setdefault((p.name, p.kind), []).append(p)
    resources = []
    for extra, metrics_by in sorted(by_resource.items()):
        metrics = []
        for (name, kind), pts in sorted(metrics_by.items()):
            dps = [{"timeUnixNano": str(p.ts_ns), "asDouble": p.value,
                    "attributes": [_kv(k, v) for k, v in sorted(p.labels.items())],
                    **({"startTimeUnixNano": str(p.start_ns)} if kind == "sum" else {})} for p in pts]
            if kind == "sum":
                metrics.append({"name": name, "sum": {"aggregationTemporality": 2, "isMonotonic": True, "dataPoints": dps}})
            else:
                metrics.append({"name": name, "gauge": {"dataPoints": dps}})
        resource = _resource(host, "xampulse-agent", None)
        resource["attributes"] += [_kv(k, v) for k, v in extra]
        resources.append({"resource": resource, "scopeMetrics": [{"scope": {"name": "xampulse-demo"}, "metrics": metrics}]})
    return {"resourceMetrics": resources}


def otlp_logs_payload(host: str, lines: List[LogLine]) -> Dict:
    groups: Dict[Tuple[str, str], List[LogLine]] = {}
    for ln in lines:
        groups.setdefault((ln.service, ln.source), []).append(ln)
    resources = []
    for (service, source), group in sorted(groups.items()):
        resources.append({"resource": _resource(host, service, source), "scopeLogs": [{"scope": {"name": "xampulse-demo"}, "logRecords": [
            {"timeUnixNano": str(ln.ts_ns), "severityText": ln.severity, "severityNumber": SEVERITY_NUMBER[ln.severity],
             "body": {"stringValue": ln.body}} for ln in group]}]})
    return {"resourceLogs": resources}


def chunk(items: List, size: int) -> List[List]:
    return [items[i:i + size] for i in range(0, len(items), size)]


def events_json_each_row(tenant: int, events: List[Event]) -> str:
    return "\n".join(json.dumps({
        "tenant_id": tenant, "ts": e.ts.strftime("%Y-%m-%d %H:%M:%S.") + f"{e.ts.microsecond // 1000:03d}",
        "kind": e.kind, "resource": e.resource, "title": e.title, "detail": e.detail}) for e in events)


# Tables that hold demo rows and the tenant-scoped predicate selecting only demo rows in each.
PURGE_STATEMENTS = [
    "ALTER TABLE xp_logs DELETE WHERE tenant_id = {tenant:UInt64} AND attrs['xp.demo'] = 'true' AND startsWith(instance_id, 'i-demo')",
    "ALTER TABLE xp_metric_points DELETE WHERE tenant_id = {tenant:UInt64} AND startsWith(instance_id, 'i-demo')",
    "ALTER TABLE xp_metrics_1m DELETE WHERE tenant_id = {tenant:UInt64} AND startsWith(instance_id, 'i-demo')",
    "ALTER TABLE xp_metrics_5m DELETE WHERE tenant_id = {tenant:UInt64} AND startsWith(instance_id, 'i-demo')",
    "ALTER TABLE xp_metrics_1h DELETE WHERE tenant_id = {tenant:UInt64} AND startsWith(instance_id, 'i-demo')",
    "ALTER TABLE xp_log_volume_1m DELETE WHERE tenant_id = {tenant:UInt64} AND startsWith(instance_id, 'i-demo')",
    "ALTER TABLE xp_events DELETE WHERE tenant_id = {tenant:UInt64} AND startsWith(resource, 'i-demo') AND startsWith(title, '[DEMO] ')",
]


# --------------------------------------------------------------------------------------------------------------------
# I/O
# --------------------------------------------------------------------------------------------------------------------
def _post(url: str, body: bytes, headers: Dict[str, str]) -> bytes:
    req = urllib.request.Request(url, data=body, headers=headers, method="POST")
    with urllib.request.urlopen(req, timeout=60) as resp:  # noqa: S310 (loopback-only, checked by assert_demo_allowed)
        return resp.read()


def _ch_headers(env: Dict[str, str]) -> Dict[str, str]:
    password = env.get("XP_CH_WRITER_PASSWORD", "")
    if not password:
        raise DemoRefused("XP_CH_WRITER_PASSWORD is not set (copy deploy/xampulse/.env.example to .env and export it).")
    token = base64.b64encode(f"{env.get('XP_CH_USER', 'xp_writer')}:{password}".encode()).decode()
    return {"Authorization": "Basic " + token}


def _ch_post(env: Dict[str, str], ch_url: str, query: str, body: bytes = b"", params: Optional[Dict[str, str]] = None) -> None:
    qs = {"database": env.get("XP_CH_DATABASE", "xampulse"), "query": query}
    for k, v in (params or {}).items():
        qs["param_" + k] = v
    _post(f"{ch_url.rstrip('/')}/?{urllib.parse.urlencode(qs)}", body, _ch_headers(env))


def seed(env: Dict[str, str], tenant: int, gateway_url: str, ch_url: str, now: dt.datetime, hours: int) -> Dict[str, int]:
    assert_demo_allowed(env, [gateway_url, ch_url])
    ds = build_dataset(now, hours=hours)
    for host, _role in HOSTS:
        headers = {"Content-Type": "application/json", "X-Xp-Tenant": str(tenant), "X-Xp-Instance": instance_id(host),
                   "X-Xp-Retain-Days": str(RETAIN_DAYS), "X-Xp-Demo": "true"}
        for part in chunk(ds.metrics[host], 12000):
            _post(gateway_url.rstrip("/") + "/v1/metrics", json.dumps(otlp_metrics_payload(host, part)).encode(), headers)
        for part in chunk(ds.logs[host], 4000):
            _post(gateway_url.rstrip("/") + "/v1/logs", json.dumps(otlp_logs_payload(host, part)).encode(), headers)
    _ch_post(env, ch_url, "INSERT INTO xp_events (tenant_id, ts, kind, resource, title, detail) FORMAT JSONEachRow",
             events_json_each_row(tenant, ds.events).encode())
    return ds.counts()


def purge(env: Dict[str, str], tenant: int, ch_url: str) -> int:
    assert_demo_allowed(env, [ch_url])
    for stmt in PURGE_STATEMENTS:
        _ch_post(env, ch_url, stmt + " SETTINGS mutations_sync = 1", params={"tenant": str(tenant)})
    return len(PURGE_STATEMENTS)


def main(argv: Optional[List[str]] = None, env: Optional[Dict[str, str]] = None) -> int:
    env = dict(os.environ) if env is None else env
    p = argparse.ArgumentParser(description="XamPulse DEMO data seeder (local development only; generated sample data).")
    p.add_argument("command", choices=["seed", "purge", "plan"], help="plan = print counts, write nothing")
    p.add_argument("--tenant", type=int, required=True, help="cloud_account_id to attach the demo data to (positive integer)")
    p.add_argument("--gateway-url", default="http://127.0.0.1:4318")
    p.add_argument("--clickhouse-url", default="http://127.0.0.1:8123")
    p.add_argument("--hours", type=int, default=24)
    args = p.parse_args(argv)
    if args.tenant <= 0:
        p.error("--tenant must be a positive integer")
    try:
        if args.command == "plan":
            ds = build_dataset(dt.datetime.now(dt.timezone.utc), hours=args.hours)
            print(json.dumps({"DEMO": True, "note": "generated sample data, nothing written", **ds.counts()}))
        elif args.command == "seed":
            print(json.dumps({"DEMO": True, "seeded": seed(env, args.tenant, args.gateway_url, args.clickhouse_url,
                                                           dt.datetime.now(dt.timezone.utc), args.hours)}))
        else:
            print(json.dumps({"DEMO": True, "purge_statements": purge(env, args.tenant, args.clickhouse_url)}))
    except DemoRefused as e:
        print(f"REFUSED: {e}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
