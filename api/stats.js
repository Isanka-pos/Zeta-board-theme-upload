'use strict';
const { getDb } = require('../lib/db');
const { isAdmin, today, daysAgo, dayRange } = require('../lib/util');

const top = (field, limit) => [
  { $group: { _id: field, n: { $sum: 1 } } },
  { $sort: { n: -1 } },
  { $limit: limit },
];
const rows = (a) => a.map((x) => ({ k: x._id === null || x._id === '' || x._id === 0 ? 'Unknown' : String(x._id), n: x.n }));

module.exports = async (req, res) => {
  try {
    if (req.method !== 'GET') { res.setHeader('Allow', 'GET'); return res.status(405).end(); }
    if (!isAdmin(req)) return res.status(401).json({ error: 'Not signed in' });

    const range = Math.min(Math.max(parseInt(req.query && req.query.days, 10) || 30, 7), 90);
    const db = await getDb();
    const devices = db.collection('devices');
    const daily = db.collection('daily');
    const events = db.collection('events');
    const t = today();
    const from = daysAgo(range - 1);
    const d7 = daysAgo(6);
    const d30 = daysAgo(29);
    const activeIn = async (since) => {
      const r = await daily.aggregate([{ $match: { d: { $gte: since } } }, { $group: { _id: '$id' } }, { $count: 'n' }]).toArray();
      return r.length ? r[0].n : 0;
    };

    const [
      total, newToday, new7, new30, active1, active7, active30,
      dailyActive, dailyNew, versions, android, brands, models, countries, langs, evCounts, themes, wallpapers, tools,
    ] = await Promise.all([
      devices.countDocuments({}),
      devices.countDocuments({ fd: t }),
      devices.countDocuments({ fd: { $gte: d7 } }),
      devices.countDocuments({ fd: { $gte: d30 } }),
      daily.countDocuments({ d: t }),
      activeIn(d7),
      activeIn(d30),
      daily.aggregate([{ $match: { d: { $gte: from } } }, { $group: { _id: '$d', n: { $sum: 1 } } }]).toArray(),
      devices.aggregate([{ $match: { fd: { $gte: from } } }, { $group: { _id: '$fd', n: { $sum: 1 } } }]).toArray(),
      devices.aggregate(top('$vn', 8)).toArray(),
      devices.aggregate(top('$sdk', 10)).toArray(),
      devices.aggregate(top('$br', 8)).toArray(),
      devices.aggregate(top('$md', 8)).toArray(),
      devices.aggregate(top('$cc', 12)).toArray(),
      devices.aggregate(top('$lg', 8)).toArray(),
      events.aggregate([{ $match: { d: { $gte: from } } }, { $group: { _id: '$e', n: { $sum: 1 } } }, { $sort: { n: -1 } }]).toArray(),
      events.aggregate([{ $match: { e: 'theme_apply', d: { $gte: from } } }, ...top('$p', 10)]).toArray(),
      events.aggregate([{ $match: { e: 'wallpaper_apply', d: { $gte: from } } }, ...top('$p', 10)]).toArray(),
      events.aggregate([{ $match: { e: 'tool', d: { $gte: from } } }, ...top('$p', 15)]).toArray(),
    ]);

    const a = new Map(dailyActive.map((x) => [x._id, x.n]));
    const n = new Map(dailyNew.map((x) => [x._id, x.n]));
    const series = dayRange(range).map((d) => ({ d, active: a.get(d) || 0, fresh: n.get(d) || 0 }));

    return res.status(200).json({
      generatedAt: new Date().toISOString(),
      today: t,
      range,
      totals: { devices: total, active1, active7, active30, newToday, new7, new30 },
      series,
      versions: rows(versions),
      android: rows(android),
      brands: rows(brands),
      models: rows(models),
      countries: rows(countries),
      langs: rows(langs),
      events: rows(evCounts),
      themes: rows(themes),
      wallpapers: rows(wallpapers),
      tools: rows(tools),
    });
  } catch (err) {
    console.error('stats failed:', err && err.message);
    return res.status(500).json({ error: 'Could not read the database' });
  }
};
