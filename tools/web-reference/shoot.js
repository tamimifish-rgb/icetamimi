// Renders the published web app with a mocked backend (no request reaches the real one)
// and saves reference screenshots. usage: node shoot.js <outDir> [role]   (see README.md)
const { chromium } = require(process.env.PLAYWRIGHT || 'playwright');
const fs = require('fs'); const path = require('path');
const fx = JSON.parse(fs.readFileSync(path.join(__dirname, process.env.FIXTURE || 'fixture.json'), 'utf8'));
fx.OWNER = fx.owner; fx.TRIP = fx.trip;
const SITE = 'https://naqi-ice-tamimi.lovable.app';
const out = process.argv[2] || 'web'; const role = process.argv[3] || 'distributor';
fs.mkdirSync(out, { recursive: true });
const fontDir = path.join(__dirname, 'node_modules/@expo-google-fonts/cairo');
const weights = { 400: '400Regular/Cairo_400Regular.ttf', 500: '500Medium/Cairo_500Medium.ttf', 600: '600SemiBold/Cairo_600SemiBold.ttf', 700: '700Bold/Cairo_700Bold.ttf' };

(async () => {
  const browser = await chromium.launch(process.env.HTTPS_PROXY ? { proxy: { server: process.env.HTTPS_PROXY } } : {});
  const context = await browser.newContext({
    viewport: { width: 411, height: Number(process.env.H || 891) }, deviceScaleFactor: 2, locale: 'ar-YE', timezoneId: 'Asia/Aden',
    geolocation: { latitude: 14.53, longitude: 49.12, accuracy: 10 }, permissions: ['geolocation'], serviceWorkers: 'block',
  });
  const now = Math.floor(Date.now() / 1000);
  const b64 = (o) => Buffer.from(JSON.stringify(o)).toString('base64url');
  const token = `${b64({ alg: 'HS256', typ: 'JWT' })}.${b64({ sub: fx.OWNER, exp: now + 86400, role: 'authenticated' })}.sig`;
  const user = { id: fx.OWNER, email: 'x@staff.pureice.internal', aud: 'authenticated', role: 'authenticated' };
  const session = { access_token: token, refresh_token: 'r', expires_in: 86400, expires_at: now + 86400, token_type: 'bearer', user };
  if (!process.env.NOSESSION) await context.addInitScript(([s]) => { localStorage.setItem('pureice-tamimi-monijxkifttaokcnyuoa-auth', s); }, [JSON.stringify(session)]);
  const tables = JSON.parse(JSON.stringify(fx.tables));
  tables.user_roles = [{ user_id: fx.OWNER, role }];
  tables.profiles = tables.profiles.map((p) => (p.id === fx.OWNER ? { ...p, full_name: role === 'distributor' ? p.full_name : 'المدير' } : p));
  const missing = [];
  await context.route('https://fonts.googleapis.com/**', (r) => r.fulfill({ contentType: 'text/css', body: Object.keys(weights).map((w) => `@font-face{font-family:'Cairo';font-weight:${w};font-style:normal;src:url(https://fonts.gstatic.com/cairo-${w}.ttf) format('truetype');}`).join('\n') }));
  await context.route('https://fonts.gstatic.com/**', (r) => { const w = /cairo-(\d+)/.exec(r.request().url())[1]; r.fulfill({ contentType: 'font/ttf', body: fs.readFileSync(path.join(fontDir, weights[w])), headers: { 'access-control-allow-origin': '*' } }); });
  await context.route('https://monijxkifttaokcnyuoa.supabase.co/**', (route) => {
    const req = route.request(); const url = new URL(req.url()); const p = url.pathname;
    const cors = { 'access-control-allow-origin': '*', 'access-control-allow-headers': '*', 'access-control-allow-methods': '*', 'access-control-expose-headers': 'content-range' };
    const json = (body, status = 200, headers = {}) => route.fulfill({ status, contentType: 'application/json', headers: { ...cors, ...headers }, body: JSON.stringify(body) });
    if (req.method() === 'OPTIONS') return route.fulfill({ status: 204, headers: cors });
    if (p === '/auth/v1/user') return json(user);
    if (p.startsWith('/auth/')) return json({});
    if (p.startsWith('/rest/v1/rpc/')) {
      const fn = p.split('/').pop();
      if (fn === 'accounting_snapshot') return json(fx.snapshot);
      if (fn === 'has_role' || fn === 'is_manager') return json(role !== 'distributor');
      if (fx.rpc && fn in fx.rpc) return json(fx.rpc[fn]);
      return json({ code: 'PGRST202', message: 'missing' }, 404);
    }
    if (p.startsWith('/rest/v1/')) {
      const table = p.split('/').pop();
      if (!(table in tables)) { if (!missing.includes(table)) missing.push(table); if (table.startsWith('pureice_')) return json({ code: 'PGRST205', message: 'missing' }, 404); }
      let rows = tables[table] || [];
      for (const [k, v] of url.searchParams) {
        if (v.startsWith('eq.')) rows = rows.filter((r) => !(k in r) || String(r[k]) === v.slice(3));
        if (v.startsWith('in.(')) { const set = v.slice(4, -1).split(',').map((x) => x.replace(/^"|"$/g, '')); rows = rows.filter((r) => !(k in r) || set.includes(String(r[k]))); }
        if (v.startsWith('neq.')) rows = rows.filter((r) => String(r[k]) !== v.slice(4));
        if (v === 'is.null') rows = rows.filter((r) => r[k] == null);
      }
      if (req.method() !== 'GET' && req.method() !== 'HEAD') return json([], 201);
      const offset = Number(url.searchParams.get('offset') || 0); const limit = url.searchParams.get('limit');
      const total = rows.length; rows = rows.slice(offset, limit ? offset + Number(limit) : undefined);
      const headers = { 'content-range': `0-${Math.max(0, rows.length - 1)}/${total}` };
      if (req.method() === 'HEAD') return route.fulfill({ status: 200, headers: { ...cors, ...headers } });
      if ((req.headers()['accept'] || '').includes('vnd.pgrst.object')) return rows[0] ? json(rows[0], 200, headers) : json({ code: 'PGRST116', message: 'no rows' }, 406);
      return json(rows, 200, headers);
    }
    return json({});
  });
  const page = await context.newPage();
  page.on('pageerror', (e) => console.log('pageerror', e.message.slice(0, 200)));
  const shot = async (name) => { await page.waitForTimeout(700); await page.screenshot({ path: path.join(out, name + '.png') }); };
  const go = async (route, name) => { await page.goto(SITE + route, { waitUntil: 'networkidle' }).catch(() => {}); await page.evaluate(() => document.fonts.ready); await shot(name); };
  const routes = JSON.parse(process.env.ROUTES || 'null') || [
    ['/today', 'today'], ['/customers', 'customers'], ['/customers/c1', 'customer'], ['/sale?customer=c1', 'sale'], ['/payment?customer=c1', 'payment'],
    ['/expenses', 'expenses'], ['/maintenance', 'maintenance'], ['/freezer-action', 'freezer'], ['/closing', 'closing'], ['/more', 'more'],
    ['/customers/new', 'new-customer'], ['/invoice/s0', 'invoice'],
  ];
  for (const [route, name] of routes) {
    await go(route, name);
    if (name === 'sale') { const plus = page.getByLabel('زيادة').first(); await plus.click(); await plus.click(); await shot('sale-filled'); }
    if (name === 'today') { await page.getByLabel('حالة المزامنة').click().catch(() => {}); await shot('sync-panel'); await page.keyboard.press('Escape'); }
    if (process.env.SCROLL) { await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight)); await shot(name + '-bottom'); }
  }
  console.log('unmocked tables:', missing.join(', '));
  await browser.close();
})();
