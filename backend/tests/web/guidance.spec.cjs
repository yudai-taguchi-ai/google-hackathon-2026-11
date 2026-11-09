const { test, expect } = require('@playwright/test');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const html = readFileSync(join(__dirname, '../../app/static/index.html'), 'utf8');
const quiet = { active: false, source: 'jma_live', jma_checked_at: '2026-11-09T12:00:00+09:00', jma_error: null };
const training = { ...quiet, active: true, source: 'training_replay', is_training: true, replaying: true, kind: '大津波警報', area_name: '大阪府' };
const live = { ...quiet, active: true, kind: '津波警報' };
function result(input = {}, alert = quiet) {
  return {
    alert,
    plan: {
      minutes_left: 17,
      time_source: alert.active ? 'jma_alert' : 'shake_plus_26',
      walking_time_source: 'routes_api',
      primary: { name: input.slow ? 'Nearby building' : 'Hotel', city: '堺市', address: '大阪府堺市', lat: 34.581, lon: 135.467, walk_min: input.slow ? 4 : 13, type: input.slow ? 'go_upstairs' : 'outside_zone', floor_min: 3 },
      alternatives: [],
    },
    explanation: { source: 'gemini', message: input.lang === 'ja' ? '画面の避難先へ向かってください。' : 'Go to the destination on the screen.' },
  };
}
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r; }); return { promise, resolve }; };
async function boot(page, overrides = {}) {
  const state = { alert: quiet, inputs: [], plan: null, ...overrides };
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.route('http://tsunami.test/**', async route => {
    const path = new URL(route.request().url()).pathname;
    if (path === '/') return route.fulfill({ contentType: 'text/html; charset=utf-8', body: html });
    if (path === '/api/alert/replay') state.alert = training;
    if (path === '/api/alert' || path === '/api/alert/replay') {
      if (state.alertFailure) return route.abort();
      return route.fulfill({ json: state.alert });
    }
    if (path === '/api/plan') {
      const input = route.request().postDataJSON();
      state.inputs.push(input);
      if (state.plan) return state.plan(route, input);
      return route.fulfill({ json: result(input, state.alert) });
    }
    return route.fulfill({ status: 404, body: '' });
  });
  await page.goto('http://tsunami.test/');
  await expect(page.locator('#refreshAlert')).toBeEnabled();
  return { state, errors };
}
async function choose(page) {
  await page.locator('button[data-lat]').first().click();
  await expect(page.locator('#out .card').first()).toBeVisible();
}

test('W-1 distinguishes a successful warning check from JMA failure and can retry', async ({ page }) => {
  const { state, errors } = await boot(page, { alert: { ...quiet, jma_error: 'network failed' } });
  await expect(page.locator('#alertbox')).toContainText('警報の有無は確認できません');
  await expect(page.locator('#alertbox')).not.toContainText('no tsunami warning');
  await choose(page);
  await expect(page.locator('#out')).toContainText('警報の有無は確認できません');
  state.alert = quiet;
  await page.locator('#refreshAlert').click();
  await expect(page.locator('#alertbox')).toContainText('no tsunami warning');
  expect(errors).toEqual([]);
});

test('W-1 preserves cached warning as unconfirmed instead of claiming a fresh check', async ({ page }) => {
  await boot(page, { alert: { ...live, jma_error: 'failed' } });
  await expect(page.locator('#alertbox')).toContainText('以前受け取った警報: 津波警報');
  await expect(page.locator('#alertbox')).toContainText('最新の状態は未確認');
});

test('W-1 transport failure and missing check metadata never mean no warning', async ({ page }) => {
  const { state } = await boot(page, { alertFailure: true });
  await expect(page.locator('#alertbox')).toContainText('警報の有無は確認できません');
  state.alertFailure = false;
  state.alert = { active: false };
  await page.locator('#refreshAlert').click();
  await expect(page.locator('#alertbox')).not.toContainText('no tsunami warning');
});

test('W-3 reload during training and actual warnings use distinct time labels', async ({ page }) => {
  const { state } = await boot(page, { alert: training });
  await expect(page.locator('#alertbox')).toContainText('Training replay');
  await choose(page);
  await expect(page.locator('#out')).toContainText('JMA arrival time (training telegram)');
  state.alert = live;
  await page.locator('#refreshAlert').click();
  await expect(page.locator('#out .card')).toBeVisible();
  await expect(page.locator('#out')).toContainText('JMA arrival time /');
  await expect(page.locator('#out')).not.toContainText('training telegram');
});

test('W-2 through W-6 reselect automatically with stable location and start time', async ({ page }, testInfo) => {
  const { state } = await boot(page);
  await choose(page);
  await expect(page.locator('#out .noalert').first()).toContainText('no tsunami warning');
  await page.locator('#replay').click();
  await expect(page.locator('#out')).toContainText('training telegram');
  await page.locator('#slow').check();
  await expect(page.locator('#out .card')).toContainText('Nearby building');
  await expect(page.locator('#out')).toContainText('0.5 m/s');
  await page.locator('#lang').selectOption('ja');
  await expect(page.locator('#out')).toContainText('画面の避難先へ');
  await expect(page.locator('#conditions')).toContainText('日本語');
  const last = state.inputs.at(-1);
  expect(last).toMatchObject({ lat: 34.5813, lon: 135.4673, slow: true, lang: 'ja' });
  expect(new Set(state.inputs.map(i => i.shake_at_ms)).size).toBe(1);
  const link = new URL(await page.locator('#out a').getAttribute('href'));
  expect(link.origin).toBe('https://www.google.com');
  expect(link.searchParams.get('travelmode')).toBe('walking');
  expect(link.searchParams.get('destination')).toBe('34.581,135.467');
  await page.screenshot({ path: testInfo.outputPath('web-guidance.png'), fullPage: true });
});

test('old plan response cannot overwrite new language, even when cancellation is ignored', async ({ page }) => {
  await page.addInitScript(() => {
    const original = window.fetch;
    window.fetch = (url, options = {}) => original(url, url === '/api/plan' ? { ...options, signal: undefined } : options);
  });
  const first = deferred();
  const release = deferred();
  await boot(page, { plan: async (route, input) => {
    if (input.lang === 'en') { first.resolve(); await release.promise; }
    await route.fulfill({ json: result(input) });
  } });
  await page.locator('button[data-lat]').first().click();
  await first.promise;
  await page.locator('#lang').selectOption('ja');
  await expect(page.locator('#out')).toContainText('画面の避難先へ');
  const oldResponse = page.waitForResponse(r => r.url().endsWith('/api/plan') && r.request().postDataJSON().lang === 'en');
  release.resolve();
  await oldResponse;
  await expect(page.locator('#out')).not.toContainText('Go to the destination');
  await expect(page.locator('#out')).toContainText('画面の避難先へ');
});

test('replay supersedes an in-flight pre-training plan', async ({ page }) => {
  await page.addInitScript(() => {
    const original = window.fetch;
    window.fetch = (url, options = {}) => original(url, url === '/api/plan' ? { ...options, signal: undefined } : options);
  });
  const pending = deferred(), release = deferred();
  let count = 0;
  await boot(page, { plan: async (route, input) => {
    if (++count === 1) { pending.resolve(); await release.promise; return route.fulfill({ json: result(input) }); }
    return route.fulfill({ json: result(input, training) });
  } });
  await page.locator('button[data-lat]').first().click();
  await pending.promise;
  await page.locator('#replay').click();
  await expect(page.locator('#out')).toContainText('training telegram');
  const oldResponse = page.waitForResponse(r => r.url().endsWith('/api/plan'));
  release.resolve();
  await oldResponse;
  await expect(page.locator('#alertbox')).toContainText('Training replay');
  await expect(page.locator('#out')).toContainText('training telegram');
});

for (const failure of ['network', 'server', 'invalid-json', 'incomplete', 'out-of-range']) {
  test(`plan failure ${failure} is retryable without resetting elapsed time`, async ({ page }) => {
    const { state, errors } = await boot(page, { plan: (route) => {
      if (failure === 'network') return route.abort();
      if (failure === 'server') return route.fulfill({ status: 500, body: 'private diagnostic' });
      if (failure === 'out-of-range') return route.fulfill({ status: 422, json: { detail: [] } });
      if (failure === 'invalid-json') return route.fulfill({ body: '<html>error</html>' });
      return route.fulfill({ json: {} });
    } });
    await page.locator('button[data-lat]').first().click();
    await expect(page.locator('#retry')).toBeVisible();
    await expect(page.locator('#out')).not.toContainText('計算中');
    await expect(page.locator('#out')).not.toContainText('private diagnostic');
    if (failure === 'out-of-range') await expect(page.locator('#out')).toContainText('対象地域外');
    state.plan = null;
    await page.locator('#retry').click();
    await expect(page.locator('#out .card')).toBeVisible();
    expect(state.inputs[1]).toEqual(state.inputs[0]);
    expect(errors).toEqual([]);
  });
}

test('plan timeout exits calculating and offers retry', async ({ page }) => {
  await page.clock.install();
  const pending = deferred();
  const release = deferred();
  await boot(page, { plan: async route => { pending.resolve(); await release.promise; await route.abort().catch(() => {}); } });
  await page.locator('button[data-lat]').first().click();
  await pending.promise;
  await page.clock.fastForward(30001);
  await expect(page.locator('#out')).toContainText('タイムアウト');
  await expect(page.locator('#retry')).toBeVisible();
  release.resolve();
});

for (const code of [1, 2, 3]) {
  test(`W-7 location error ${code} can retry and recover`, async ({ page }) => {
    await page.addInitScript(code => {
      window.geoAttempts = 0;
      Object.defineProperty(navigator, 'geolocation', { configurable: true, value: {
        getCurrentPosition(ok, fail) {
          if (++window.geoAttempts === 1) fail({ code });
          else ok({ coords: { latitude: 34.5813, longitude: 135.4673 } });
        },
      } });
    }, code);
    await boot(page);
    await page.locator('#here').click();
    await expect(page.locator('#retry')).toBeVisible();
    await expect(page.locator('#out')).toContainText(['', '許可されていません', '取得できませんでした', 'タイムアウト'][code]);
    await page.locator('#retry').click();
    await expect(page.locator('#out .card')).toBeVisible();
    await expect(page.locator('#conditions')).toContainText('現在地');
  });
}

test('late GPS result cannot replace a location selected by the user', async ({ page }) => {
  await page.addInitScript(() => {
    Object.defineProperty(navigator, 'geolocation', { value: { getCurrentPosition(ok) { window.finishGps = ok; } } });
  });
  const { state } = await boot(page);
  await page.locator('#here').click();
  await choose(page);
  await page.evaluate(() => window.finishGps({ coords: { latitude: 35, longitude: 135 } }));
  await expect(page.locator('#conditions')).toContainText('Nankai Sakai');
  expect(state.inputs).toHaveLength(1);
});

test('W-8 long content fits narrow screens and response text is escaped', async ({ page }, testInfo) => {
  const { errors } = await boot(page, { plan: (route, input) => {
    const data = result(input);
    data.plan.primary.name = '<img src=x onerror=alert(1)>' + 'LongDestination'.repeat(30);
    data.explanation.message = 'A'.repeat(400);
    return route.fulfill({ json: data });
  } });
  await choose(page);
  await expect(page.locator('#out img')).toHaveCount(0);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.screenshot({ path: testInfo.outputPath('web-layout.png'), fullPage: true });
  expect(errors).toEqual([]);
  expect(html).not.toMatch(/\?{3,}/);
});
test('warning check latency does not postpone the evacuation start time', async ({ page }) => {
  await page.clock.install();
  const { state } = await boot(page);
  const pending = deferred(), release = deferred();
  await page.route('http://tsunami.test/api/alert', async route => {
    pending.resolve();
    await release.promise;
    await route.fulfill({ json: quiet });
  });
  await page.locator('#refreshAlert').click();
  await pending.promise;
  await page.locator('button[data-lat]').first().click();
  const selectedAt = await page.evaluate(() => Date.now());
  await expect(page.locator('#out')).toContainText('警報の確認後に計算します');
  await page.clock.fastForward(5000);
  release.resolve();
  await expect(page.locator('#out .card')).toBeVisible();
  expect(state.inputs[0].shake_at_ms).toBeLessThanOrEqual(selectedAt);
  expect(state.inputs[0].shake_at_ms).toBeGreaterThan(selectedAt - 1000);
});
