// GET /api/wallpapers  ->  { items: [ {id,name,category,thumb,full,width,height,size} ] }
// No file-system access and no dependencies: the list is a JSON file bundled with the function,
// so this cannot crash with FUNCTION_INVOCATION_FAILED (Vercel's file system is read-only).
const data = require("../data/wallpapers.json");

module.exports = (req, res) => {
  try {
    res.setHeader("Content-Type", "application/json; charset=utf-8");
    res.setHeader("Cache-Control", "public, max-age=0, s-maxage=60, stale-while-revalidate=300");
    res.setHeader("Access-Control-Allow-Origin", "*");
    const items = Array.isArray(data.items) ? data.items : [];
    res.status(200).send(JSON.stringify({ items }));
  } catch (e) {
    res.status(200).send(JSON.stringify({ items: [] }));
  }
};
