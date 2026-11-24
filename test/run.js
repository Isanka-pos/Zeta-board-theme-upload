'use strict';
// Offline tests: the database is replaced by a tiny in-memory fake, so no MongoDB is needed.
const path = require('path');
const assert = require('assert');
process.env.ADMIN_PASSWORD = 'pw-test';
process.env.SESSION_SECRET = 's3';
process.env.APP_KEY = 'key123';

const calls = [];
const fakeDb = {
  collection: (name) => ({
    updateOne: async (...a) => { calls.push([name, 'updateOne', ...a]); },
    insertOne: async (...a) => { calls.push([name, 'insertOne', ...a]); },
    countDocuments: async () => 3,
    aggregate: () => ({ toArray: async () => [{ _id: 'x', n: 2 }] }),
  }),
};
const dbPath = require.resolve('../lib/db');
require.cache[dbPath] = { id: dbPath, filename: dbPath, loaded: true, exports: { getDb: async () => fakeDb } };

const track = require('../api/track');
const login = require('../api/login');
const stats = require('../api/stats');

function mockRes() {
  const r = { code: 200, headers: {}, body: undefined, ended: false };
  r.status = (c) => { r.code = c; return r; };
  r.setHeader = (k, v) => { r.headers[k] = v; };
  r.json = (b) => { r.body = b; r.ended = true; return r; };
  r.end = () => { r.ended = true; return r; };
  return r;
}
const run = async (h, req) => { const res = mockRes(); await h(Object.assign({ headers: {}, query: {} }, req), res); return res; };
const ID = '123e4567-e89b-42d3-a456-426614174000';
const good = { id: ID, e: 'theme_apply', p: 'Hacker', v: 12, vn: '1.0.12', sdk: 34, br: 'samsung', md: 'SM-A546E', lg: 'si' };

(async () => {
  let r;
  r = await run(track, { method: 'GET' }); assert.strictEqual(r.code, 405);
  r = await run(track, { method: 'POST', body: good }); assert.strictEqual(r.code, 401, 'missing app key');
  r = await run(track, { method: 'POST', headers: { 'x-app-key': 'nope' }, body: good }); assert.strictEqual(r.code, 401);
  r = await run(track, { method: 'POST', headers: { 'x-app-key': 'key123' }, body: { ...good, id: 'bad' } }); assert.strictEqual(r.code, 400);
  r = await run(track, { method: 'POST', headers: { 'x-app-key': 'key123' }, body: { ...good, e: 'drop_tables' } }); assert.strictEqual(r.code, 400);
  calls.length = 0;
  r = await run(track, { method: 'POST', headers: { 'x-app-key': 'key123', 'x-vercel-ip-country': 'lk' }, body: good });
  assert.strictEqual(r.code, 204);
  assert.strictEqual(calls.length, 3);
  assert.strictEqual(calls[0][3].$set.cc, 'LK');
  calls.length = 0;
  r = await run(track, { method: 'POST', headers: { 'x-app-key': 'key123' }, body: JSON.stringify({ ...good, e: 'open' }) });
  assert.strictEqual(r.code, 204); assert.strictEqual(calls.length, 2, 'open writes device + daily only');
  r = await run(track, { method: 'POST', headers: { 'x-app-key': 'key123' }, body: '{bad json' }); assert.strictEqual(r.code, 400);
  const evil = await run(track, { method: 'POST', headers: { 'x-app-key': 'key123' }, body: { ...good, p: '<img src=x onerror=1>"' } });
  assert.strictEqual(evil.code, 204);
  assert.ok(!JSON.stringify(calls.slice(-1)).includes('<'), 'markup stripped');

  r = await run(stats, { method: 'GET' }); assert.strictEqual(r.code, 401);
  r = await run(login, { method: 'POST', body: { password: 'wrong' } }); assert.strictEqual(r.code, 401);
  r = await run(login, { method: 'POST', body: { password: 'pw-test' } });
  assert.strictEqual(r.code, 200);
  const cookie = r.headers['Set-Cookie'].split(';')[0];
  assert.ok(/HttpOnly/.test(r.headers['Set-Cookie']) && /Secure/.test(r.headers['Set-Cookie']));
  r = await run(stats, { method: 'GET', headers: { cookie }, query: { days: '14' } });
  assert.strictEqual(r.code, 200); assert.strictEqual(r.body.series.length, 14); assert.strictEqual(r.body.totals.devices, 3);
  r = await run(stats, { method: 'GET', headers: { cookie: 'zs_session=123.abc' } }); assert.strictEqual(r.code, 401);
  r = await run(stats, { method: 'GET', headers: { cookie }, query: { days: 'abc' } }); assert.strictEqual(r.body.series.length, 30);
  console.log('all tests passed');
})().catch((e) => { console.error('FAILED', e); process.exit(1); });
