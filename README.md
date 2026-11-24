# Zeta Board Stats

Anonymous usage statistics dashboard for the Zeta Board keyboard. Vercel (serverless functions + static page) and MongoDB Atlas.

## What it shows
Total installs, daily/7-day/30-day active users, new installs, app versions, Android versions, countries, phone brands/models,
languages, top themes, top wallpapers, most used tools. Auto-refreshes every minute. Time zone: Sri Lanka (UTC+5:30).

## Deploy (about 5 minutes)
1. Put this folder in its own GitHub repo (do **not** commit `.env`; it is git-ignored).
2. Vercel -> Add New -> Project -> import the repo -> Deploy.
3. Vercel -> Project -> Settings -> Environment Variables, add (all environments), then **Redeploy**:

| Name | Value |
|---|---|
| `MONGODB_URI` | your Atlas connection string (with a **new** password, see below) |
| `MONGODB_DB` | `zeta_stats` (any name; created automatically) |
| `ADMIN_PASSWORD` | a long password for the dashboard |
| `SESSION_SECRET` | any long random text |
| `APP_KEY` | any random text; the app sends it so strangers cannot post fake data |

4. MongoDB Atlas -> Network Access -> **Add IP Address -> Allow access from anywhere (0.0.0.0/0)**. Vercel has no fixed IP, so this is required. The database user's password is what protects it.
5. Open `https://YOUR-PROJECT.vercel.app` and sign in.
6. Firebase Remote Config: `stats_url` = `https://YOUR-PROJECT.vercel.app`, `stats_key` = the same text as `APP_KEY`. Publish. Phones that have the new app version start reporting (stop any time by clearing `stats_url`).

## Security notes
* Never put the MongoDB password in code or chat. It lives only in Vercel environment variables.
* The dashboard is protected by a signed, HttpOnly session cookie (12 hours). Wrong passwords are slowed down and temporarily blocked.
* `/api/track` accepts only a fixed list of event names, validates and cleans every field, and needs the `APP_KEY`.
* Raw events are deleted automatically after about 400 days.

## Local checks
`npm install && npm test` (offline tests with an in-memory fake database).

## Files
`api/track.js` (app reports), `api/stats.js` (dashboard data), `api/login.js` (sign in/out), `public/index.html` (dashboard), `lib/` (database + helpers).
