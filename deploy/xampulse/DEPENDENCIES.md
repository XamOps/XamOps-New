# XamPulse dev-stack dependencies

Only Apache-2.0 / MIT / ISC / MPL-2.0 components. Exact pins, verified by pulling the tags on 2026-11-02.

| Component | Pin | Licence | Used for |
|---|---|---|---|
| ClickHouse server | `clickhouse/clickhouse-server:25.8.33.6` | Apache-2.0 | hot store for logs, metrics, events |
| OpenTelemetry Collector (contrib) | `otel/opentelemetry-collector-contrib:0.131.0` | Apache-2.0 | OTLP/HTTP gateway, ClickHouse exporter |
| Python standard library only | 3.9+ | PSF | demo seeder (`demo/xp_demo_seed.py`) and its tests |

The frontend pins (`uplot`, `@tanstack/react-virtual`) belong to slice C and are recorded by that slice.
