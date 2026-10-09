# NowFocus API runbook

Production: `https://api.nowfocus.online` (Cloudflare, then nginx, then pm2 app `nowfocus-api` on `127.0.0.1:3000`, local Postgres). One instance only: the WebSocket hub and rate limits are in memory.

## Release

**Automatic:** every push to `main` that touches `services/api/` runs the `deploy` job in `.github/workflows/api.yml` after the tests pass. It SSHes in with `API_DEPLOY_SSH_KEY`, whose `authorized_keys` line forces `deploy/release.sh` (it accepts only a 40-char commit sha), then polls `/healthz` until `sha` matches. One-time setup:

1. `ssh-keygen -t ed25519 -N '' -C nowfocus-api-deploy -f ~/nf-api-deploy` on your laptop.
2. On the VPS, `git pull` once so `release.sh` exists, then append to `~/.ssh/authorized_keys`: `restrict,command="<repo>/services/api/deploy/release.sh" <contents of nf-api-deploy.pub>`. Check `command -v git node pnpm pm2 flock` works over a non-interactive ssh (`ssh host 'command -v pnpm'` goes through the same minimal PATH; extend the `PATH` line in `release.sh` if not).
3. `gh secret set API_DEPLOY_SSH_KEY < ~/nf-api-deploy && rm ~/nf-api-deploy ~/nf-api-deploy.pub`. `DEPLOY_HOST` and `DEPLOY_KNOWN_HOSTS` are shared with the web deploy.

By hand, same script: `deploy/release.sh <full sha>` on the VPS. Or step by step, in the checkout:

```sh
git pull
cd services/api
pnpm install --frozen-lockfile
git rev-parse HEAD > REVISION          # gitignored; /healthz reports it
pnpm run migrate                       # builds, then applies migrations. Run from services/api so .env loads.
pm2 restart nowfocus-api --update-env && pm2 save
curl -s http://127.0.0.1:3000/healthz  # "sha" must be the commit you just pulled
```

From anywhere, afterwards: `node services/api/deploy/smoke.mjs https://api.nowfocus.online --idle=150` (creates and deletes one throwaway account; the idle period is what exercises the nginx and Cloudflare timeouts).

**Rollback:** migrations are additive, so the previous build still runs against a migrated database. `git switch --detach <previous sha>`, repeat the block above from `pnpm install`, and check `/healthz`.

**nginx real client IP:** the per-IP login throttle needs the real client address. On the production box `nginx.conf` already sets `real_ip_header` globally; do **not** install `cloudflare-realip.sh` there as well (nginx rejects a duplicate and `nginx -t` fails). Use the script only on a box where `grep -rn real_ip /etc/nginx` finds nothing. The api vhost must send `X-Forwarded-For $remote_addr` (overwrite, never `$proxy_add_x_forwarded_for`).

## Backups

Twice a day `backup.sh` dumps the database, encrypts it with an `age` public key, copies it off the server with rclone, and prunes (7 days local, 30 days remote). The dump holds emails and password hashes, so the **private key must never be on the VPS**: without it the backups are unreadable, and with it on the VPS a break-in could read them.

One-time setup:

1. **Key pair, on your laptop.** `age-keygen -o nowfocus-backup-key.txt` prints `Public key: age1...`. Keep the file in your password manager (losing it means losing every backup), then delete the loose copy.
2. **Off-box storage (Cloudflare R2).** Dashboard: R2 object storage, enable it (needs a payment method on file; this volume stays inside the free tier), create bucket `nowfocus-backups`, then *Manage API tokens* and create a token with **Object Read & Write** limited to that bucket. Copy the Access Key ID and Secret (shown once). Any other rclone destination (Backblaze B2, SFTP to another machine) works the same way.
3. **On the VPS.** `sudo apt install -y age rclone`, then configure rclone without leaving the secrets in shell history:
   ```sh
   read -rsp "Access Key ID: " AK; echo; read -rsp "Secret: " SK; echo
   rclone config create r2 s3 provider=Cloudflare access_key_id="$AK" secret_access_key="$SK" \
     endpoint="https://<ACCOUNT_ID>.r2.cloudflarestorage.com" acl=private no_check_bucket=true
   unset AK SK
   echo ok > /tmp/hello.txt && rclone copyto /tmp/hello.txt r2:nowfocus-backups/db/hello.txt && rclone lsf r2:nowfocus-backups/db && rclone deletefile r2:nowfocus-backups/db/hello.txt; rm /tmp/hello.txt
   ```
   Use `copyto`, not `rcat`: `backup.sh` uploads a real file, and R2 rejects `rcat`'s streamed upload with `501 NotImplemented`. An old apt rclone may also log one `501` on attempt 1 and succeed on attempt 2; that is harmless (the command exits 0), and the current build from `curl https://rclone.org/install.sh | sudo bash` avoids it.
4. **Settings file** `~/.config/nowfocus-backup.env`, `chmod 600`:
   ```
   AGE_RECIPIENT=age1...your public key...
   RCLONE_DEST=r2:nowfocus-backups/db
   HEALTHCHECK_URL=https://hc-ping.com/<uuid>   # optional dead-man switch (healthchecks.io): alerts when a backup does NOT run
   ```
5. **First run, by hand:** `<repo>/services/api/deploy/backup.sh` prints `ok nowfocus-<time>.dump.age (<n> bytes)`. Confirm the file is in the bucket.
6. **Cron** (`crontab -e`, server time is Europe/Berlin): `17 3,15 * * * <repo>/services/api/deploy/backup.sh >> $HOME/backups/nowfocus/backup.log 2>&1`

### Restore drill (do it once now, then every quarter)

On your laptop, with a scratch database. Fetch a backup from the bucket (dashboard or `rclone copyto`), then:

```sh
createdb nowfocus_restore_check
age -d -i nowfocus-backup-key.txt nowfocus-<time>.dump.age | pg_restore --no-owner -d nowfocus_restore_check
psql nowfocus_restore_check -c "select count(*) from users"       # compare with the same query on the VPS
dropdb nowfocus_restore_check
```

Real disaster, on the VPS: stop the app (`pm2 stop nowfocus-api`), recreate an empty database, run the same pipeline against it with `pg_restore --no-owner --clean --if-exists -d <db>`, start the app, and check `/healthz`. `pg_restore` must be the same Postgres major version as the server or newer. Clients keep enforcing from local state meanwhile; the server is authoritative only for Commitment Shield items and trusted time.

## Secrets

- **`JWT_SECRET` rotation:** change it in `.env` and restart. Only 15-minute access tokens die; refresh tokens are hashed in the database and survive, so devices stay signed in.
- **`DATABASE_URL` / DB password:** change the role password, update `.env`, restart the app. `backup.sh` reads the same `.env`.

## Monitoring checklist

Tick each when done on the VPS or the monitoring site (done 2026-10-04 to 2026-10-07):

- [x] **External uptime monitor:** free UptimeRobot or Better Stack check on `https://api.nowfocus.online/healthz`, 5-minute interval, alert by email. It must check the 200 status or the `"ok"` body, so a 503 `db_unavailable` also alerts.
- [x] **Backup cron installed:** step 6 above; `crontab -l` shows the `backup.sh` line.
- [x] **Backup dead-man switch:** `HEALTHCHECK_URL` set in `~/.config/nowfocus-backup.env` (healthchecks.io, period 12h, grace 2h).
- [x] **Restore drill done once** (confirmed 2026-10-07), then every quarter (see above). Next due: 2027-01.
- [x] **`pm2 startup` set** (confirmed 2026-10-07), so the API comes back after a reboot.
- [x] **Manual `backup.sh` run** shipped an encrypted dump to R2 and pinged healthchecks.io green (confirmed 2026-10-07).

## When `/healthz` fails

`pm2 logs nowfocus-api --lines 50 --nostream`. A 503 `db_unavailable` means Postgres is unreachable (`systemctl status postgresql`). A missing `REVISION` shows `"sha":"dev"`, so redo the release block. Wrong sha after a release means pm2 is still running the old build: `pm2 restart nowfocus-api --update-env`.

## Admin dashboard

`https://dash.nowfocus.online` lists waitlist signups and feature requests, shows which version each person was emailed, and exports CSV. Enable it by adding `ADMIN_TOKEN=$(openssl rand -hex 32)` to the VPS `.env` and running `pm2 restart nowfocus-api`. Unset = the routes return 404. Rotate by changing the value and restarting.

It is the same pm2 app as the API behind a second nginx vhost. nginx config is not deployed by CI, so on the VPS (once, and again whenever the `.conf` files change):

```sh
sudo cp services/api/deploy/dash.nowfocus.online.conf /etc/nginx/sites-available/dash.nowfocus.online   # then ln -s into sites-enabled, as for api
sudo nginx -t && sudo systemctl reload nginx
sudo certbot --nginx -d dash.nowfocus.online        # the proxied A record `dash` already exists in Cloudflare; the zone is SSL "full" with Always Use HTTPS off, so the HTTP-01 challenge passes the proxy (if that ever changes, set the record to DNS-only for the issue)
# once https://dash.nowfocus.online works, block the old URL on the API host:
sudo cp services/api/deploy/api.nowfocus.online.conf /etc/nginx/sites-available/api.nowfocus.online   # certbot's 443 block lives in this file: merge the two `location` lines by hand instead of overwriting
sudo nginx -t && sudo systemctl reload nginx
```

## Emailing testers about a new version

The `waitlist_sends` table records every (person, platform, version) emailed, so a run never sends the same version twice and an interrupted run can be repeated. From `services/api` on the VPS (after the release containing the command is live):

```sh
pnpm run notify --platform android --version 0.9 --url "<Play testing opt-in link>" --notes "Fixes X|Adds Y"   # dry run: counts and first 5 addresses
pnpm run notify ... --to you@example.com [--locale ar]                                                            # one preview, records nothing
pnpm run notify ... --send                                                                                        # sends, records each success
```

**Before the first Android `--send`:** with Play *closed* testing the opt-in link only works for Google accounts on the tester list, and waitlist emails are not necessarily Google accounts. Export the android rows from the dashboard CSV (filter Platform = android) and add them to the testers list in Play Console, or use open testing. Otherwise people get a link that says they can't join.

If a run stops on Brevo errors such as a daily sending limit, fix the cause or wait, then run the same command again: people already sent that version are skipped.

Windows defaults `--url` to `https://api.nowfocus.online/v1/downloads/windows`. Recipients: picked that platform and have not unsubscribed. Every email carries a one-click unsubscribe link (`unsubscribed_at` on the waitlist row). `locale` defaults to `en` for people who signed up before it was stored; fix individuals with `update waitlist set locale='ar' where email = '...'`.
