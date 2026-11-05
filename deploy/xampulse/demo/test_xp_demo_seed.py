"""Unit tests for the XamPulse demo seeder logic. Run: python3 -m unittest discover -s deploy/xampulse/demo"""
import datetime as dt
import json
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
import xp_demo_seed as d  # noqa: E402

NOW = dt.datetime(2026, 11, 2, 12, 0, 0, tzinfo=dt.timezone.utc)
ON = {d.DEMO_FLAG_ENV: "true"}


class GuardTest(unittest.TestCase):
    def test_off_by_default(self):
        with self.assertRaises(d.DemoRefused):
            d.assert_demo_allowed({}, ["http://127.0.0.1:4318"])
        with self.assertRaises(d.DemoRefused):
            d.assert_demo_allowed({d.DEMO_FLAG_ENV: "false"}, ["http://127.0.0.1:4318"])

    def test_loopback_only(self):
        d.assert_demo_allowed(ON, ["http://127.0.0.1:4318", "http://localhost:8123", "http://[::1]:8123"])
        for bad in ("https://xampulse.example.com", "http://10.0.0.5:4318", "http://203.0.113.9:8123"):
            with self.assertRaises(d.DemoRefused):
                d.assert_demo_allowed(ON, [bad])

    def test_cli_refuses_without_flag_and_writes_nothing(self):
        self.assertEqual(d.main(["seed", "--tenant", "7"], env={}), 2)
        self.assertEqual(d.main(["purge", "--tenant", "7"], env={}), 2)

    def test_cli_refuses_remote(self):
        self.assertEqual(d.main(["seed", "--tenant", "7", "--gateway-url", "https://prod.example.com"], env=ON), 2)


class DeterminismTest(unittest.TestCase):
    def test_same_seed_same_output(self):
        a = d.build_dataset(NOW, hours=2)
        b = d.build_dataset(NOW, hours=2)
        self.assertEqual(json.dumps(d.otlp_metrics_payload("demo-web-1", a.metrics["demo-web-1"])),
                         json.dumps(d.otlp_metrics_payload("demo-web-1", b.metrics["demo-web-1"])))
        self.assertEqual([(x.ts_ns, x.body) for x in a.logs["demo-api-1"]], [(x.ts_ns, x.body) for x in b.logs["demo-api-1"]])

    def test_different_seed_differs(self):
        a = d.build_dataset(NOW, hours=1)
        b = d.build_dataset(NOW, hours=1, seed=7)
        self.assertNotEqual([p.value for p in a.metrics["demo-web-1"]], [p.value for p in b.metrics["demo-web-1"]])

    def test_requires_tz_aware_now(self):
        with self.assertRaises(ValueError):
            d.build_dataset(dt.datetime(2026, 11, 2, 12, 0, 0))


class DemoMarkingTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.ds = d.build_dataset(NOW, hours=24)

    def test_six_demo_hosts(self):
        self.assertEqual(sorted(self.ds.metrics), ["demo-api-1", "demo-api-2", "demo-db-1", "demo-web-1", "demo-web-2", "demo-web-3"])
        for host in self.ds.metrics:
            self.assertTrue(host.startswith(d.DEMO_HOST_PREFIX))
            self.assertTrue(d.instance_id(host).startswith(d.DEMO_INSTANCE_PREFIX))

    def test_every_metric_point_is_marked(self):
        for pts in self.ds.metrics.values():
            for p in pts:
                self.assertEqual(p.labels["xp.demo"], "true")
                self.assertTrue(p.labels["host.name"].startswith("demo-"))

    def test_every_event_is_marked(self):
        self.assertGreaterEqual(len(self.ds.events), 8)
        for e in self.ds.events:
            self.assertTrue(e.title.startswith("[DEMO] "))
            self.assertTrue(e.resource.startswith("i-demo"))

    def test_payload_resources_use_demo_hostnames(self):
        payload = d.otlp_logs_payload("demo-web-1", self.ds.logs["demo-web-1"][:50])
        for res in payload["resourceLogs"]:
            attrs = {a["key"]: a["value"]["stringValue"] for a in res["resource"]["attributes"]}
            self.assertEqual(attrs["host.name"], "demo-web-1")

    def test_payload_never_carries_trusted_tenant_attrs(self):
        # tenant/instance/demo stamps must come from gateway headers, never from the generated payload
        for payload in (d.otlp_logs_payload("demo-db-1", self.ds.logs["demo-db-1"][:100]),
                        d.otlp_metrics_payload("demo-db-1", self.ds.metrics["demo-db-1"][:100])):
            text = json.dumps(payload)
            for key in ("xp.tenant_id", "xp.instance_id", "xp.retain_days"):
                self.assertNotIn(f'"{key}"', text)

    def test_no_future_timestamps_and_24h_span(self):
        now_ns = d._ns(NOW)
        for host, lines in self.ds.logs.items():
            self.assertTrue(all(l.ts_ns <= now_ns for l in lines), host)
        first = min(p.ts_ns for p in self.ds.metrics["demo-web-1"])
        self.assertEqual(first, d._ns(NOW - dt.timedelta(hours=24)))

    def test_row_counts(self):
        c = self.ds.counts()
        self.assertEqual(c["hosts"], 6)
        # 1441 ticks x 12 series per host
        self.assertEqual(len(self.ds.metrics["demo-web-1"]), 1441 * 12)
        self.assertGreater(c["log_lines"], 3000)


class EpisodeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.ds = d.build_dataset(NOW, hours=24)

    def _series(self, host, name, **labels):
        return [(p.ts_ns, p.value) for p in self.ds.metrics[host]
                if p.name == name and all(p.labels.get(k) == v for k, v in labels.items())]

    def test_cpu_steal_episode_on_web3_only(self):
        lo, hi = d._ns(NOW - dt.timedelta(hours=3)), d._ns(NOW - dt.timedelta(hours=2))
        inside = [v for t, v in self._series("demo-web-3", "system.cpu.utilization", state="steal") if lo <= t < hi]
        outside = [v for t, v in self._series("demo-web-3", "system.cpu.utilization", state="steal") if t < lo or t >= hi]
        self.assertGreater(min(inside), 0.08)
        self.assertLess(max(outside), 0.02)
        self.assertLess(max(v for _, v in self._series("demo-web-1", "system.cpu.utilization", state="steal")), 0.02)

    def test_disk_filling_on_db1(self):
        s = self._series("demo-db-1", "system.filesystem.utilization")
        self.assertLess(s[0][1], 0.66)
        self.assertGreater(s[-1][1], 0.90)

    def test_oom_burst_logs_on_api2(self):
        lo, hi = d._ns(NOW - dt.timedelta(minutes=90)), d._ns(NOW - dt.timedelta(minutes=80))
        oom = [l for l in self.ds.logs["demo-api-2"] if "Out of memory" in l.body or "OutOfMemoryError" in l.body]
        self.assertGreaterEqual(len(oom), 10)
        self.assertTrue(all(lo - 40_000_000_000 <= l.ts_ns < hi for l in oom))
        self.assertFalse([l for l in self.ds.logs["demo-api-1"] if "Out of memory" in l.body])

    def test_new_error_pattern_only_last_25_minutes(self):
        errs = [l for l in self.ds.logs["demo-api-1"] if "payment-gateway timeout" in l.body]
        self.assertGreaterEqual(len(errs), 8)
        self.assertTrue(all(l.severity == "ERROR" for l in errs))
        self.assertGreaterEqual(min(l.ts_ns for l in errs), d._ns(NOW - dt.timedelta(minutes=25)) - 40_000_000_000)

    def test_deploy_and_ec2_events_present(self):
        kinds = {e.kind for e in self.ds.events}
        self.assertTrue({"deploy", "ec2.state", "alert.fired", "alert.resolved"} <= kinds)


class WireFormatTest(unittest.TestCase):
    def test_events_rows_json(self):
        ds = d.build_dataset(NOW, hours=1)
        rows = [json.loads(x) for x in d.events_json_each_row(99, ds.events).splitlines()]
        self.assertEqual(len(rows), len(ds.events))
        self.assertTrue(all(r["tenant_id"] == 99 and r["title"].startswith("[DEMO] ") for r in rows))
        self.assertRegex(rows[0]["ts"], r"^\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d{3}$")

    def test_purge_statements_are_tenant_scoped_and_demo_only(self):
        self.assertEqual(len(d.PURGE_STATEMENTS), 7)
        for stmt in d.PURGE_STATEMENTS:
            self.assertIn("tenant_id = {tenant:UInt64}", stmt)       # bound parameter, never interpolated
            self.assertIn("i-demo", stmt)
            self.assertNotRegex(stmt, r"tenant_id = \d")

    def test_chunking(self):
        self.assertEqual([len(c) for c in d.chunk(list(range(10)), 4)], [4, 4, 2])


if __name__ == "__main__":
    unittest.main()
