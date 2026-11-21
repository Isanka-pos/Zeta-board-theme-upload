# Zeta Wallpaper Server (Vercel-safe)

Static files + one tiny JSON function. No database, no uploads at runtime, no npm packages,
so it cannot fail with `500 FUNCTION_INVOCATION_FAILED` (Vercel's disk is read-only, so an
`/admin` upload page cannot work there - wallpapers are added by pushing files instead).

## Add wallpapers
1. Put pictures in `wallpapers/<Category>/name.jpg` (jpg / png / webp). The folder name is the category tab name.
2. Run `pip install pillow` once, then `python3 tools/make_wallpapers.py`
   (it makes `public/files/*` and `data/wallpapers.json`).
3. Commit and push. Vercel redeploys by itself and the app shows the new wallpapers.

(The `wallpapers/` folder holds your originals; delete the 6 sample gradients any time, then run step 2 again.)

## Vercel settings
- Framework Preset: **Other**
- Root Directory: the folder that contains `vercel.json` (this folder)
- Build Command: leave empty - Output Directory: leave empty (the `public` folder is served automatically)
- Test: open `https://YOUR-PROJECT.vercel.app/api/wallpapers` - you must see JSON with `"items"`.

## Zeta Board app
GitHub repo -> Settings -> Secrets and variables -> Actions -> Variables -> `WALLPAPER_URL` = `https://YOUR-PROJECT.vercel.app`
(no slash at the end), then push to build a new APK.
