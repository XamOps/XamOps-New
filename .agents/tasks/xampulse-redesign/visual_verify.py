import base64
import json
import os
import signal
import subprocess
import time
import urllib.request

import websocket

ROOT = "/Users/admin/Desktop/xam-latest/XamOps-New"
FRONTEND = f"{ROOT}/frontend-app"
OUT = f"{ROOT}/.agents/tasks/xampulse-redesign/screenshots"
BASE = "http://127.0.0.1:4174"
CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"

os.makedirs(OUT, exist_ok=True)
results = []
console_errors = []
processes = []


def wait_url(url, timeout=30):
    end = time.time() + timeout
    while time.time() < end:
        try:
            return urllib.request.urlopen(url, timeout=1).read()
        except Exception:
            time.sleep(0.2)
    raise RuntimeError(f"Timed out waiting for {url}")


def start(cmd, cwd=None, env=None):
    proc = subprocess.Popen(cmd, cwd=cwd, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, preexec_fn=os.setsid)
    processes.append(proc)
    return proc


try:
    env = os.environ.copy()
    env["VITE_XAMPULSE_DEMO"] = "true"
    start(["npm", "run", "dev", "--", "--host", "127.0.0.1", "--port", "4174"], cwd=FRONTEND, env=env)
    wait_url(f"{BASE}/xampulse/overview")

    profile = f"/tmp/xampulse-visual-chrome-{os.getpid()}-{int(time.time())}"
    debug_port = 9300 + (os.getpid() % 500)
    start([
        CHROME, "--headless=new", "--disable-gpu", "--no-sandbox", "--hide-scrollbars",
        f"--remote-debugging-port={debug_port}", "--remote-allow-origins=*", f"--user-data-dir={profile}",
        "--force-device-scale-factor=1", f"{BASE}/xampulse/overview",
    ])
    pages = json.loads(wait_url(f"http://127.0.0.1:{debug_port}/json/list"))
    page = next(item for item in pages if item.get("type") == "page")
    ws = websocket.create_connection(page["webSocketDebuggerUrl"], timeout=30)
    next_id = 0

    def command(method, params=None):
        global next_id
        next_id += 1
        wanted = next_id
        ws.send(json.dumps({"id": wanted, "method": method, "params": params or {}}))
        while True:
            message = json.loads(ws.recv())
            if message.get("method") == "Runtime.exceptionThrown":
                console_errors.append(message.get("params", {}).get("exceptionDetails", {}).get("text", "Runtime exception"))
            if message.get("method") == "Log.entryAdded" and message.get("params", {}).get("entry", {}).get("level") == "error":
                console_errors.append(message["params"]["entry"].get("text", "Console error"))
            if message.get("id") == wanted:
                if "error" in message:
                    raise RuntimeError(f"{method}: {message['error']}")
                return message.get("result", {})

    def evaluate(expression):
        return command("Runtime.evaluate", {"expression": expression, "returnByValue": True, "awaitPromise": True}).get("result", {}).get("value")

    command("Runtime.enable")
    command("Log.enable")
    command("Page.enable")
    command("Emulation.setEmulatedMedia", {"features": [{"name": "prefers-reduced-motion", "value": "reduce"}]})
    time.sleep(0.8)
    evaluate("localStorage.setItem('xp:prefs:v1', JSON.stringify({theme:'light',density:'comfortable',logWrap:false})); localStorage.setItem('xp:demo:scenario','populated')")

    def navigate(path, scenario="populated", loading=False):
        evaluate(f"localStorage.setItem('xp:demo:scenario', {json.dumps(scenario)})")
        command("Page.navigate", {"url": BASE + path})
        end = time.time() + 15
        while time.time() < end:
            if evaluate("document.readyState") == "complete":
                break
            time.sleep(0.15)
        time.sleep(0.8 if loading else 2.0)

    def capture(name, path, width, height, scenario="populated", loading=False, action=None):
        command("Emulation.setDeviceMetricsOverride", {
            "width": width, "height": height, "deviceScaleFactor": 1, "mobile": width <= 500,
            "screenWidth": width, "screenHeight": height,
        })
        navigate(path, scenario, loading)
        if action:
            evaluate(action)
            time.sleep(0.5)
        metrics = evaluate("""(() => {
          const root = document.documentElement;
          const activeBefore = document.activeElement;
          if (activeBefore instanceof HTMLElement) activeBefore.blur();
          return {
            title: document.title,
            h1: Array.from(document.querySelectorAll('h1')).map(x => x.textContent.trim()),
            width: innerWidth,
            documentWidth: root.scrollWidth,
            overflow: root.scrollWidth > innerWidth + 1,
            height: Math.max(root.scrollHeight, document.body?.scrollHeight || 0),
            reducedMotion: matchMedia('(prefers-reduced-motion: reduce)').matches,
            skeletons: document.querySelectorAll('[aria-busy=true], .xp-skeleton').length,
            errors: document.querySelectorAll('[role=alert], .xp-error').length,
            buttons: document.querySelectorAll('button').length,
            links: document.querySelectorAll('a').length
          };
        })()""")
        command("Input.dispatchKeyEvent", {"type": "keyDown", "key": "Tab", "code": "Tab", "windowsVirtualKeyCode": 9})
        command("Input.dispatchKeyEvent", {"type": "keyUp", "key": "Tab", "code": "Tab", "windowsVirtualKeyCode": 9})
        focus = evaluate("""(() => { const e=document.activeElement; const s=e instanceof HTMLElement ? getComputedStyle(e) : null; return {tag:e?.tagName, text:(e?.textContent||'').trim().slice(0,80), focusVisible:e instanceof HTMLElement ? e.matches(':focus-visible') : false, outline:s?.outline, boxShadow:s?.boxShadow}; })()""")
        layout = command("Page.getLayoutMetrics").get("cssContentSize", {})
        full_height = min(12000, max(height, int(layout.get("height", metrics["height"]))))
        shot = command("Page.captureScreenshot", {
            "format": "png", "fromSurface": True, "captureBeyondViewport": True,
            "clip": {"x": 0, "y": 0, "width": width, "height": full_height, "scale": 1},
        })
        target = f"{OUT}/{name}.png"
        with open(target, "wb") as handle:
            handle.write(base64.b64decode(shot["data"]))
        results.append({"name": name, "path": target, "route": path, "scenario": scenario, "viewport": [width, height], "metrics": metrics, "focus": focus})

    routes = [
        ("overview", "/xampulse/overview"),
        ("hosts", "/xampulse/hosts"),
        ("containers", "/xampulse/containers"),
        ("logs", "/xampulse/logs"),
        ("metrics", "/xampulse/metrics"),
        ("alerts", "/xampulse/alerts"),
        ("insights", "/xampulse/insights"),
        ("setup", "/xampulse/setup"),
        ("host-360", "/xampulse/hosts/i-demo0005?accountId=1"),
        ("container-360", "/xampulse/containers/i-demo0005/api?accountId=1"),
    ]
    viewports = [("desktop", 1440, 900), ("tablet", 1024, 768), ("narrow", 390, 844)]
    for route_name, path in routes:
        for viewport_name, width, height in viewports:
            capture(f"{route_name}-{viewport_name}", path, width, height)

    for scenario in ["loading", "empty", "partial", "error", "setup-required"]:
        capture(f"overview-state-{scenario}", "/xampulse/overview", 1440, 900, scenario, scenario == "loading")

    capture("setup-usage-deeplink-desktop", "/xampulse/setup?view=usage&accounts=1", 1440, 900)
    capture("hosts-map-desktop", "/xampulse/hosts", 1440, 900, action="Array.from(document.querySelectorAll('button')).find(x => x.textContent.trim() === 'Map')?.click()")
    capture("hosts-columns-desktop", "/xampulse/hosts", 1440, 900, action="Array.from(document.querySelectorAll('button')).find(x => x.textContent.trim() === 'Columns')?.click()")

    with open(f"{OUT}/visual-results.json", "w") as handle:
        json.dump({"results": results, "consoleErrors": console_errors}, handle, indent=2)
    print(json.dumps({
        "screenshots": len(results),
        "overflow": [item["name"] for item in results if item["metrics"]["overflow"]],
        "missingH1": [item["name"] for item in results if not item["metrics"]["h1"]],
        "focusFailures": [item["name"] for item in results if not item["focus"]["focusVisible"]],
        "consoleErrors": console_errors,
        "results": f"{OUT}/visual-results.json",
    }, indent=2))
finally:
    for proc in reversed(processes):
        try:
            os.killpg(os.getpgid(proc.pid), signal.SIGTERM)
        except Exception:
            pass
