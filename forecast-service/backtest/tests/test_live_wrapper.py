import gzip
import json
import subprocess
import urllib.parse

import pytest

from backtest.data import xamops_live as live

CTX = "arn:aws:eks:ap-south-1:102227938259:cluster/xamops-test-1"


@pytest.mark.parametrize("args", [
    ["--raw", "/api/v1/namespaces/monitoring/services/prometheus-server:80/proxy/api/v1/query?query=up"],
    ["svc", "-A"],
    ["pods", "-n", "monitoring", "-o", "json"],
])
def test_argv_has_context_and_get(args):
    argv = live.build_argv(args)
    assert argv[:4] == ["kubectl", "--context", CTX, "get"]
    assert argv[4:] == args
    assert argv.count("--context") == 1


@pytest.mark.parametrize("args", [
    ["config", "use-context", "other"],
    ["config"],
    ["--raw", "/x", "--context", "other"],
    ["--raw", "/x", "--context=other"],
    ["--raw", "/x", "--kubeconfig", "/tmp/k"],
    [],
    ["-n", "x"],
])
def test_argv_rejects(args):
    with pytest.raises(ValueError):
        live.build_argv(args)


def _matrix(labels, values):
    return {"metric": labels, "values": [[t, str(v)] for t, v in values]}


def test_fetch_uses_wrapper_and_hashes_names(tmp_path, monkeypatch):
    end = 100 * 3600
    start = end - 80 * 3600
    hours = range(start, end + 1, 3600)
    ns, pod, ctr = "secret-ns", "secret-pod-abc", "secret-ctr"
    lbl = {"namespace": ns, "pod": pod, "container": ctr}
    calls = []

    def fake_run(argv, **kw):
        calls.append(argv)
        q = urllib.parse.parse_qs(urllib.parse.urlparse(argv[-1]).query)["query"][0]
        if q == live.Q_OLDEST:
            result = [{"metric": {}, "value": [end, str(start - 10)]}]
        elif q == live.Q_CPU:
            result = [_matrix(lbl, [(t, 0.1 + 0.01 * (i % 5)) for i, t in enumerate(hours)])]
        elif q == live.Q_MEM:
            result = [_matrix(lbl, [(t, 1e8 + 1e6 * (i % 7)) for i, t in enumerate(hours)]),
                      _matrix({**lbl, "container": "no-request"}, [(t, 5e7) for t in hours])]
        elif q == live.Q_REQ:
            result = [_matrix({**lbl, "resource": "cpu"}, [(t, 0.2) for t in hours]),
                      _matrix({**lbl, "resource": "memory"}, [(t, 2e8) for t in hours])]
        else:
            raise AssertionError(q)
        body = {"status": "success", "data": {"result": result}}
        return subprocess.CompletedProcess(argv, 0, json.dumps(body), "")

    monkeypatch.setattr(live.subprocess, "run", fake_run)
    path = tmp_path / "sample.csv.gz"
    stats = live.fetch(seed=1, path=path, now=end + 59)
    assert len(calls) == 4
    for argv in calls:
        assert argv[:5] == ["kubectl", "--context", CTX, "get", "--raw"]
        assert "config" not in argv
    assert stats["kept"] == {"cpu": 1, "mem": 1}
    assert stats["dropped"] == {"mem_no_request": 1}
    text = gzip.decompress(path.read_bytes()).decode()
    for raw in (ns, pod, ctr, "no-request"):
        assert raw not in text
    series = live.load(10, seed=1, path=path)
    assert sorted(s.resource for s in series) == ["cpu", "mem"]
    cpu = next(s for s in series if s.resource == "cpu")
    assert cpu.unit == "pct_request" and len(cpu) == 81
    assert cpu.values[0] == pytest.approx(50.0)
