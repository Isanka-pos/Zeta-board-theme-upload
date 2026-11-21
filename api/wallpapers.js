// GET /api/wallpapers -> { items: [...] }  built from the pictures in public/w/<Category>/
// Read-only file access only (allowed on Vercel); any problem returns an empty list, never a crash.
const fs = require("fs");
const path = require("path");

function jpegSize(file) {
  try {
    const b = fs.readFileSync(file);
    let i = 2;
    while (i < b.length - 9) {
      if (b[i] !== 0xff) { i++; continue; }
      const m = b[i + 1];
      if (m >= 0xc0 && m <= 0xcf && m !== 0xc4 && m !== 0xc8 && m !== 0xcc) {
        return { h: b.readUInt16BE(i + 5), w: b.readUInt16BE(i + 7) };
      }
      i += 2 + b.readUInt16BE(i + 2);
    }
  } catch (e) {}
  return { w: 0, h: 0 };
}

function pretty(id) {
  const s = id.replace(/-[a-z0-9]{4}$/, "").replace(/-+/g, " ").trim();
  return s ? s[0].toUpperCase() + s.slice(1) : "Wallpaper";
}

function list() {
  const root = path.join(process.cwd(), "public", "w");
  const items = [];
  let cats = [];
  try { cats = fs.readdirSync(root); } catch (e) { return items; }
  for (const cat of cats.sort()) {
    let files = [];
    try { files = fs.readdirSync(path.join(root, cat)); } catch (e) { continue; }
    for (const f of files.sort()) {
      if (!f.endsWith(".jpg") || f.endsWith("_t.jpg")) continue;
      const id = f.slice(0, -4);
      if (files.indexOf(id + "_t.jpg") < 0) continue;
      const full = path.join(root, cat, f);
      let size = 0; try { size = fs.statSync(full).size; } catch (e) {}
      const d = jpegSize(full);
      const enc = encodeURIComponent(cat);
      items.push({
        id, name: pretty(id), category: cat,
        thumb: "/w/" + enc + "/" + id + "_t.jpg",
        full: "/w/" + enc + "/" + id + ".jpg",
        width: d.w, height: d.h, size,
      });
    }
  }
  return items;
}

module.exports = (req, res) => {
  res.setHeader("Content-Type", "application/json; charset=utf-8");
  res.setHeader("Cache-Control", "public, max-age=0, s-maxage=30, stale-while-revalidate=120");
  res.setHeader("Access-Control-Allow-Origin", "*");
  let items = [];
  try { items = list(); } catch (e) {}
  res.status(200).send(JSON.stringify({ items }));
};
