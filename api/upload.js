// POST /api/upload {password, category, name, full, thumb}  (full/thumb = base64 JPEG)
const { commit, dir, authed } = require("./_gh");

module.exports = async (req, res) => {
  res.setHeader("Content-Type", "application/json; charset=utf-8");
  try {
    if (req.method !== "POST") return res.status(405).send('{"error":"POST only"}');
    const b = req.body || {};
    if (!authed(b)) return res.status(401).send('{"error":"Wrong password"}');
    const cat = String(b.category || "").trim();
    if (!/^[A-Za-z0-9 ]{1,24}$/.test(cat)) return res.status(400).send('{"error":"Category: letters, numbers, spaces only"}');
    const slug = String(b.name || "wallpaper").toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "").slice(0, 40) || "wallpaper";
    const id = slug + "-" + Math.random().toString(36).slice(2, 6);
    if (!b.full || !b.thumb) return res.status(400).send('{"error":"Missing image"}');
    const base = dir() + "public/w/" + cat + "/" + id;
    await commit([
      { path: base + ".jpg", base64: b.full },
      { path: base + "_t.jpg", base64: b.thumb },
    ], "Add wallpaper " + id);
    res.status(200).send(JSON.stringify({ ok: true, id }));
  } catch (e) {
    res.status(500).send(JSON.stringify({ error: String(e.message || e) }));
  }
};
