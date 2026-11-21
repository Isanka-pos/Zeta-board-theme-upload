// POST /api/delete {password, category, id}
const { commit, dir, authed } = require("./_gh");

module.exports = async (req, res) => {
  res.setHeader("Content-Type", "application/json; charset=utf-8");
  try {
    if (req.method !== "POST") return res.status(405).send('{"error":"POST only"}');
    const b = req.body || {};
    if (!authed(b)) return res.status(401).send('{"error":"Wrong password"}');
    const cat = String(b.category || ""), id = String(b.id || "");
    if (!/^[A-Za-z0-9 ]{1,24}$/.test(cat) || !/^[a-z0-9-]{1,60}$/.test(id)) return res.status(400).send('{"error":"Bad id"}');
    const base = dir() + "public/files/" + cat + "/" + id;
    await commit([{ path: base + ".jpg", remove: true }, { path: base + "_t.jpg", remove: true }], "Remove wallpaper " + id);
    res.status(200).send('{"ok":true}');
  } catch (e) {
    res.status(500).send(JSON.stringify({ error: String(e.message || e) }));
  }
};
