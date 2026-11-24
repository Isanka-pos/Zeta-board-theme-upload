'use strict';
const { getDb } = require('../lib/db');
const { clean, safeEqual, readJson, today } = require('../lib/util');

// Events the app may send. Anything else is ignored, so nobody can fill the database with junk names.
const EVENTS = new Set(['open', 'theme_apply', 'wallpaper_apply', 'tool', 'language', 'update_install']);
const ID_RE = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;

module.exports = async (req, res) => {
  try {
    if (req.method !== 'POST') { res.setHeader('Allow', 'POST'); return res.status(405).end(); }

    const appKey = process.env.APP_KEY;
    if (appKey && !safeEqual(req.headers['x-app-key'] || '', appKey)) return res.status(401).end();

    let b;
    try { b = await readJson(req); } catch (e) { return res.status(400).end(); }
    if (!b || typeof b !== 'object') return res.status(400).end();

    const id = clean(b.id, 36).toLowerCase();
    const e = clean(b.e, 24);
    if (!ID_RE.test(id) || !EVENTS.has(e)) return res.status(400).end();
    const p = clean(b.p, 40);

    const now = new Date();
    const d = today();
    const code = Number.isInteger(b.v) && b.v > 0 && b.v < 1e7 ? b.v : 0;
    const sdk = Number.isInteger(b.sdk) && b.sdk > 0 && b.sdk < 100 ? b.sdk : 0;
    const cc = clean(String(req.headers['x-vercel-ip-country'] || ''), 2).toUpperCase() || 'XX';

    const db = await getDb();
    const set = { ls: now, lsd: d, vc: code, vn: clean(b.vn, 16), sdk, br: clean(b.br, 24), md: clean(b.md, 40), lg: clean(b.lg, 8), cc };
    await Promise.all([
      db.collection('devices').updateOne(
        { _id: id },
        { $set: set, $setOnInsert: { fd: d, ft: now }, $inc: { n: 1 } },
        { upsert: true }
      ),
      db.collection('daily').updateOne({ _id: d + '_' + id }, { $setOnInsert: { d, id, t: now } }, { upsert: true }),
      e === 'open' ? Promise.resolve() : db.collection('events').insertOne({ t: now, d, id, e, p }),
    ]);
    return res.status(204).end();
  } catch (err) {
    console.error('track failed:', err && err.message);
    return res.status(500).json({ error: 'server' });
  }
};
