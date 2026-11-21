# Zeta Wallpaper Server (Vercel)

Add wallpapers from a web page: `https://YOUR-PROJECT.vercel.app/admin`

How it works: the page shrinks the picture in your browser, sends it to `/api/upload`, which saves it
into this GitHub repo (`public/w/<Category>/`). Vercel then redeploys by itself (about 1 minute) and
the app list (`/api/wallpapers`) shows it. Vercel's disk is read-only, so saving through GitHub is the way.

## One-time setup
1. GitHub -> Settings -> Developer settings -> Personal access tokens -> Fine-grained -> Generate:
   only this repo, permission **Contents: Read and write**. Copy the token.
2. Vercel project -> Settings -> Environment Variables (add all, then Redeploy):
   - `ADMIN_PASSWORD` = a password you choose
   - `GITHUB_TOKEN`   = the token from step 1
   - `GITHUB_REPO`    = `Isanka-pos/Zeta-board-theme-upload` (owner/repo)
   - `GITHUB_BRANCH`  = `main` (optional)
   - `GITHUB_DIR`     = folder of this project inside the repo, e.g. `zeta-wallpaper-server/` (leave out if these files are at the repo root)
3. Open `/admin`, type the password, category, pick pictures, Upload.

## Vercel settings
Framework Preset: Other. Build/Output command: empty. Test `/api/wallpapers` -> JSON with "items".

## App
GitHub repo of the app -> Settings -> Secrets and variables -> Actions -> Variables -> `WALLPAPER_URL` = `https://YOUR-PROJECT.vercel.app`
