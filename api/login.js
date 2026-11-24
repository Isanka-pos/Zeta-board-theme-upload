'use strict';
const { safeEqual, readJson, makeToken } = require('../lib/util');

const TTL = 12 * 60 * 60 * 1000;
// Very small in-memory brake against password guessing (per serverless instance).
const fails = new Map();

module.exports = async (req, res) => {
  try {
    if (req.method === 'DELETE') {
      res.setHeader('Set-Cookie', 'zs_session=; Path=/; Max-Age=0; HttpOnly; Secure; SameSite=Strict');
      return res.status(204).end();
    }
    if (req.method !== 'POST') { res.setHeader('Allow', 'POST, DELETE'); return res.status(405).end(); }
    if (!process.env.ADMIN_PASSWORD) return res.status(500).json({ error: 'ADMIN_PASSWORD is not set on the server' });

    const ip = String(req.headers['x-forwarded-for'] || 'x').split(',')[0].trim();
    const rec = fails.get(ip) || { n: 0, until: 0 };
    if (rec.until > Date.now()) return res.status(429).json({ error: 'Too many attempts. Try again in a few minutes.' });

    let b;
    try { b = await readJson(req, 1024); } catch (e) { return res.status(400).json({ error: 'Bad request' }); }
    const ok = b && typeof b.password === 'string' && safeEqual(b.password, process.env.ADMIN_PASSWORD);
    if (!ok) {
      rec.n += 1;
      if (rec.n >= 5) { rec.until = Date.now() + 10 * 60000; rec.n = 0; }
      fails.set(ip, rec);
      await new Promise((r) => setTimeout(r, 600));
      return res.status(401).json({ error: 'Wrong password' });
    }
    fails.delete(ip);
    const token = makeToken(TTL);
    res.setHeader('Set-Cookie', 'zs_session=' + encodeURIComponent(token) + '; Path=/; Max-Age=' + TTL / 1000 + '; HttpOnly; Secure; SameSite=Strict');
    return res.status(200).json({ ok: true });
  } catch (err) {
    console.error('login failed:', err && err.message);
    return res.status(500).json({ error: 'server' });
  }
};
