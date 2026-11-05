// XamPulse demo-mode visual verification. Starts its OWN Vite dev server (port 5180, VITE_XAMPULSE_DEMO=true)
// through the Vite API, screenshots every /xampulse/<tab> in light and dark, then closes only that server.
// Auth is mocked in the browser only (page.route on /api/**); no backend or real session is touched.
// Usage: node visual-verify.mjs  (run from anywhere)
import { createRequire } from 'node:module';
import path from 'node:path';
import fs from 'node:fs';

const FRONTEND = '/Users/admin/Desktop/xam-latest/XamOps-New/frontend-app';
const OUT = '/Users/admin/Desktop/xam-latest/XamOps-New/.agents/tasks/observability-1-0/screenshots';
const PW = process.env.PW_PATH || '/Users/admin/.npm/_npx/2c10347c988d3245/node_modules/playwright';
const PORT = 5180;
const TABS = ['overview', 'hosts', 'containers', 'logs', 'metrics', 'alerts', 'insights', 'setup'];

const req = createRequire(path.join(FRONTEND, 'package.json'));
const { createServer } = await import(path.join(FRONTEND, 'node_modules/vite/dist/node/index.js'));
const { chromium } = req(PW);

process.env.VITE_XAMPULSE_DEMO = 'true';
process.chdir(FRONTEND);
const server = await createServer({ root: FRONTEND, server: { port: PORT, strictPort: true }, logLevel: 'warn' });
await server.listen();
fs.mkdirSync(OUT, { recursive: true });

const profile = { email: 'xampulse.verify@example.com', username: 'xampulse-verify', roles: ['ROLE_XAMOPS'], authorities: ['ROLE_XAMOPS'] };
const results = [];
const browser = await chromium.launch();
try {
  for (const theme of ['light', 'dark']) {
    const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
    await ctx.addInitScript((t) => {
      localStorage.setItem('xp:prefs:v1', JSON.stringify({ theme: t, density: 'comfortable' }));
      // Skip the app-wide XamOps first-login onboarding modal (App.tsx OnboardingController) for the mock user.
      localStorage.setItem('xamops_onboarding_v1_xampulse.verify@example.com', 'done');
    }, theme);
    // Only backend calls (pathname starts with /api/); /src/api/*.ts are Vite modules and must pass through.
    await ctx.route((u) => u.pathname.startsWith('/api/'), (route) => {
      const u = new URL(route.request().url());
      if (/\/(user\/profile|billops\/profile)$/.test(u.pathname)) return route.fulfill({ json: profile });
      if (/account/i.test(u.pathname)) return route.fulfill({ json: [] });
      return route.fulfill({ json: {} });
    });
    for (const tab of TABS) {
      const page = await ctx.newPage();
      const errors = [];
      page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text().slice(0, 300)); });
      page.on('pageerror', (e) => errors.push(`pageerror: ${String(e.message).slice(0, 300)}`));
      await page.goto(`http://localhost:${PORT}/xampulse/${tab}`, { waitUntil: 'load', timeout: 30000 }).catch((e) => errors.push(`goto: ${e.message}`));
      await page.locator('.xp-root').first().waitFor({ timeout: 20000 }).catch(() => errors.push('no .xp-root within 20s'));
      await page.waitForTimeout(2500);
      const url = new URL(page.url()).pathname;
      const banner = await page.getByText(/DEMO DATA: generated sample data/).count();
      const h1 = await page.locator('main h1, .xp-main h1').first().textContent().catch(() => null);
      const dataTheme = await page.locator('.xp-root').first().getAttribute('data-theme').catch(() => null);
      const file = path.join(OUT, `${tab}-${theme}.png`);
      await page.screenshot({ path: file, fullPage: true });
      results.push({ tab, theme, url, banner: banner > 0, h1: h1?.trim() ?? null, dataTheme, errors });
      await page.close();
    }
    await ctx.close();
  }
} finally {
  await browser.close();
  await server.close();
}
fs.writeFileSync(path.join(OUT, 'results.json'), JSON.stringify(results, null, 2));
for (const r of results) console.log(`${r.tab}/${r.theme} url=${r.url} banner=${r.banner} theme=${r.dataTheme} h1=${JSON.stringify(r.h1)} errors=${r.errors.length}`);
