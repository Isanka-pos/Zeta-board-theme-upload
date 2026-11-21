// Tiny GitHub helper: makes ONE commit (add and/or delete files) so Vercel redeploys once.
const API = "https://api.github.com";

async function gh(path, opts) {
  const r = await fetch(API + path, Object.assign({}, opts, {
    headers: {
      Authorization: "Bearer " + process.env.GITHUB_TOKEN,
      Accept: "application/vnd.github+json",
      "User-Agent": "zeta-wallpaper-admin",
      "Content-Type": "application/json",
    },
  }));
  const t = await r.text();
  if (!r.ok) throw new Error("GitHub " + r.status + ": " + t.slice(0, 200));
  return t ? JSON.parse(t) : {};
}

// files: [{path, base64}] to add, [{path, remove:true}] to delete
async function commit(files, message) {
  const repo = process.env.GITHUB_REPO;
  const branch = process.env.GITHUB_BRANCH || "main";
  if (!process.env.GITHUB_TOKEN || !repo) throw new Error("GITHUB_TOKEN / GITHUB_REPO not set");
  const ref = await gh("/repos/" + repo + "/git/ref/heads/" + branch);
  const base = ref.object.sha;
  const baseCommit = await gh("/repos/" + repo + "/git/commits/" + base);
  const tree = [];
  for (const f of files) {
    if (f.remove) {
      tree.push({ path: f.path, mode: "100644", type: "blob", sha: null });
    } else {
      const b = await gh("/repos/" + repo + "/git/blobs", {
        method: "POST", body: JSON.stringify({ content: f.base64, encoding: "base64" }),
      });
      tree.push({ path: f.path, mode: "100644", type: "blob", sha: b.sha });
    }
  }
  const nt = await gh("/repos/" + repo + "/git/trees", {
    method: "POST", body: JSON.stringify({ base_tree: baseCommit.tree.sha, tree }),
  });
  const nc = await gh("/repos/" + repo + "/git/commits", {
    method: "POST", body: JSON.stringify({ message, tree: nt.sha, parents: [base] }),
  });
  await gh("/repos/" + repo + "/git/refs/heads/" + branch, {
    method: "PATCH", body: JSON.stringify({ sha: nc.sha }),
  });
}

function dir() {
  let d = process.env.GITHUB_DIR || "";
  if (d && !d.endsWith("/")) d += "/";
  return d;
}

function authed(body) {
  const p = process.env.ADMIN_PASSWORD;
  return !!p && body && body.password === p;
}

module.exports = { commit, dir, authed };
