#!/usr/bin/env node
/*
 * Self-contained Postman collection runner.
 *
 * Newman and postman-runtime are the normal way to run these, but neither can be
 * installed in this environment (no network to the npm registry). This runner
 * depends only on Node built-ins. It:
 *   - loads a v2.1 collection and an environment file,
 *   - substitutes {{VARS}} from the environment,
 *   - sends each request over http/https,
 *   - runs the embedded `pm.test(...)` scripts against a small pm/chai shim,
 *   - prints a Newman-style summary and exits non-zero on any failure.
 *
 * Usage: node run-collection.js <collection.json> <environment.json>
 */
const fs = require('fs');
const http = require('http');
const https = require('https');
const { URL } = require('url');
const vm = require('vm');

const collectionPath = process.argv[2] || 'xamops-collection.json';
const environmentPath = process.argv[3] || 'xamops-environment.local.json';

const collection = JSON.parse(fs.readFileSync(collectionPath, 'utf8'));
const envJson = JSON.parse(fs.readFileSync(environmentPath, 'utf8'));

const env = {};
for (const v of envJson.values || []) {
  if (v.enabled !== false) env[v.key] = v.value;
}

function substitute(str) {
  if (typeof str !== 'string') return str;
  return str.replace(/\{\{([^}]+)\}\}/g, (_, name) => (env[name] !== undefined ? env[name] : ''));
}

function flatten(items, trail, out) {
  for (const it of items || []) {
    if (it.item) flatten(it.item, trail.concat(it.name), out);
    else if (it.request) out.push({ folder: trail.join(' / '), item: it });
  }
}

function buildUrl(request) {
  const u = request.url;
  const raw = typeof u === 'string' ? u : u.raw;
  return substitute(raw);
}

function sendRequest(request) {
  return new Promise((resolve) => {
    const started = Date.now();
    let target;
    try {
      target = new URL(buildUrl(request));
    } catch (e) {
      return resolve({ error: 'Invalid URL: ' + e.message });
    }
    const lib = target.protocol === 'https:' ? https : http;
    const headers = {};
    for (const h of request.header || []) {
      if (h.disabled) continue;
      headers[substitute(h.key)] = substitute(h.value);
    }
    let body;
    if (request.body && request.body.mode === 'raw' && request.body.raw) {
      body = substitute(request.body.raw);
      if (!Object.keys(headers).some(k => k.toLowerCase() === 'content-type')) {
        headers['Content-Type'] = 'application/json';
      }
    }
    const opts = {
      method: request.method || 'GET',
      hostname: target.hostname,
      port: target.port,
      path: target.pathname + target.search,
      headers,
      timeout: 15000,
    };
    let settled = false;
    const finish = (r) => { if (!settled) { settled = true; resolve(r); } };
    const req = lib.request(opts, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => {
        finish({
          code: res.statusCode,
          status: res.statusMessage,
          headers: res.headers,
          text: Buffer.concat(chunks).toString('utf8'),
          responseTime: Date.now() - started,
        });
      });
    });
    req.on('timeout', () => { req.destroy(new Error('request timed out after 15000ms')); });
    req.on('error', (e) => finish({ error: `${e.code || ''} ${e.message}`.trim(), responseTime: Date.now() - started }));
    if (body) req.write(body);
    req.end();
  });
}

// Minimal chai-style expectation + pm shim to run the collections' test scripts.
function makeExpect(fails) {
  return function expect(actual) {
    const api = {
      to: null,
      _negate: false,
    };
    const assert = (cond, msg) => { if (api._negate ? cond : !cond) throw new Error(msg); };
    const chain = {
      get to() { return chain; },
      get be() { return chain; },
      get have() { return chain; },
      get an() { return chain.a; },
      a(type) {
        const t = Array.isArray(actual) ? 'array' : typeof actual;
        assert(t === type, `expected ${JSON.stringify(actual)} to be a ${type}`);
        return chain;
      },
      eql(exp) { assert(JSON.stringify(actual) === JSON.stringify(exp), `expected ${JSON.stringify(actual)} to equal ${JSON.stringify(exp)}`); return chain; },
      equal(exp) { return chain.eql(exp); },
      below(n) { assert(actual < n, `expected ${actual} to be below ${n}`); return chain; },
      above(n) { assert(actual > n, `expected ${actual} to be above ${n}`); return chain; },
      oneOf(arr) { assert(arr.includes(actual), `expected ${actual} to be one of ${JSON.stringify(arr)}`); return chain; },
      status(code) { assert(actual === code, `expected status ${actual} to be ${code}`); return chain; },
    };
    return chain;
  };
}

function runTests(scriptLines, response, testResults) {
  const pm = {
    environment: {
      get: (k) => (env[k] === '' || env[k] === undefined ? undefined : env[k]),
      set: (k, v) => { env[k] = v; },
    },
    response: {
      code: response.code,
      responseTime: response.responseTime,
      to: {
        have: { status: (c) => { if (response.code !== c) throw new Error(`expected status ${response.code} to be ${c}`); } },
      },
      json: () => JSON.parse(response.text),
    },
    expect: makeExpect(),
    test: (name, fn) => {
      try { fn(); testResults.push({ name, passed: true }); }
      catch (e) { testResults.push({ name, passed: false, error: e.message }); }
    },
  };
  const code = Array.isArray(scriptLines) ? scriptLines.join('\n') : String(scriptLines);
  try {
    vm.runInNewContext(code, { pm, console });
  } catch (e) {
    testResults.push({ name: '(script execution)', passed: false, error: e.message });
  }
}

(async function main() {
  const flat = [];
  flatten(collection.item, [], flat);

  console.log(`\n=== ${collection.info && collection.info.name || 'Collection'} ===`);
  console.log(`environment: ${envJson.name} | requests: ${flat.length}\n`);

  let totalTests = 0, passed = 0, failed = 0, requestErrors = 0;

  for (const { folder, item } of flat) {
    const testResults = [];
    const response = await sendRequest(item.request);
    const label = `${folder ? folder + ' / ' : ''}${item.name}`;

    if (response.error) {
      requestErrors++;
      console.log(`  [REQUEST ERROR] ${label}`);
      console.log(`      -> ${response.error} (${response.responseTime ?? '?'} ms)`);
      continue;
    }

    console.log(`  ${label} -> HTTP ${response.code} ${response.status || ''} (${response.responseTime} ms)`);

    const testEvent = (item.event || []).find(e => e.listen === 'test');
    if (testEvent && testEvent.script) {
      runTests(testEvent.script.exec, response, testResults);
    }
    for (const t of testResults) {
      totalTests++;
      if (t.passed) { passed++; console.log(`      ok   ${t.name}`); }
      else { failed++; console.log(`      FAIL ${t.name} :: ${t.error}`); }
    }
  }

  console.log('\n--- totals ---');
  console.log(`requests: ${flat.length}, request errors (could not connect): ${requestErrors}`);
  console.log(`assertions: ${totalTests}, passed: ${passed}, failed: ${failed}`);

  process.exit(requestErrors > 0 || failed > 0 ? 1 : 0);
})();
