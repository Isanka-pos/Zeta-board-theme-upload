'use strict';
const crypto = require('crypto');

const TZ_OFFSET_MIN = parseInt(process.env.TZ_OFFSET_MIN || '330', 10); // Sri Lanka = UTC+5:30

/** "YYYY-MM-DD" in the dashboard's time zone. */
function dayOf(ms) {
  return new Date(ms + TZ_OFFSET_MIN * 60000).toISOString().slice(0, 10);
}
function today() { return dayOf(Date.now()); }
function daysAgo(n) { return dayOf(Date.now() - n * 86400000); }

/** The last n days (oldest first) as strings. */
function dayRange(n) {
  const out = [];
  for (let i = n - 1; i >= 0; i--) out.push(daysAgo(i));
  return out;
}

function clean(v, max) {
  if (typeof v !== 'string') return '';
  // keep printable characters only, no control chars / markup
  return v.replace(/[\u0000-\u001f\u007f<>"'`\\]/g, '').trim().slice(0, max);
}

function safeEqual(a, b) {
  const ha = crypto.createHash('sha256').update(String(a)).digest();
  const hb = crypto.createHash('sha256').update(String(b)).digest();
  return crypto.timingSafeEqual(ha, hb);
}

function secret() {
  return (process.env.SESSION_SECRET || '') + '|' + (process.env.ADMIN_PASSWORD || '');
}

function sign(payload) {
  return crypto.createHmac('sha256', secret()).update(payload).digest('base64url');
}

/** token = "<expiryMs>.<signature>" */
function makeToken(ttlMs) {
  const exp = String(Date.now() + ttlMs);
  return exp + '.' + sign(exp);
}

function checkToken(token) {
  if (!process.env.ADMIN_PASSWORD || typeof token !== 'string') return false;
  const i = token.indexOf('.');
  if (i < 1) return false;
  const exp = token.slice(0, i);
  const sig = token.slice(i + 1);
  if (!/^\d{10,15}$/.test(exp) || Number(exp) < Date.now()) return false;
  return safeEqual(sig, sign(exp));
}

function readCookie(req, name) {
  const raw = (req.headers && req.headers.cookie) || '';
  for (const part of raw.split(';')) {
    const eq = part.indexOf('=');
    if (eq < 0) continue;
    if (part.slice(0, eq).trim() === name) {
      try { return decodeURIComponent(part.slice(eq + 1).trim()); } catch (e) { return ''; }
    }
  }
  return '';
}

function isAdmin(req) {
  const auth = (req.headers && req.headers.authorization) || '';
  const bearer = auth.startsWith('Bearer ') ? auth.slice(7) : '';
  return checkToken(readCookie(req, 'zs_session')) || checkToken(bearer);
}

/** Reads a small JSON body no matter how the platform delivered it. */
async function readJson(req, limit = 4096) {
  if (req.body && typeof req.body === 'object' && !Buffer.isBuffer(req.body)) return req.body;
  let raw = '';
  if (typeof req.body === 'string') raw = req.body;
  else if (Buffer.isBuffer(req.body)) raw = req.body.toString('utf8');
  else {
    raw = await new Promise((resolve, reject) => {
      let size = 0;
      const chunks = [];
      req.on('data', (c) => {
        size += c.length;
        if (size > limit) { reject(new Error('too large')); return; }
        chunks.push(c);
      });
      req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
      req.on('error', reject);
    });
  }
  if (raw.length > limit) throw new Error('too large');
  return raw ? JSON.parse(raw) : {};
}

module.exports = { dayOf, today, daysAgo, dayRange, clean, safeEqual, makeToken, checkToken, isAdmin, readJson, readCookie };
