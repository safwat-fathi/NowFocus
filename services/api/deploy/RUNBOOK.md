# NowFocus API runbook

Production: `https://api.nowfocus.online` (Cloudflare, then nginx, then pm2 app `nowfocus-api` on `127.0.0.1:3000`, local Postgres). One instance only: the WebSocket hub and rate limits are in memory.

## Release

On the VPS, in the checkout:

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
   echo ok | rclone rcat r2:nowfocus-backups/db/hello.txt && rclone lsf r2:nowfocus-backups/db && rclone deletefile r2:nowfocus-backups/db/hello.txt
   ```
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

## When `/healthz` fails

`pm2 logs nowfocus-api --lines 50 --nostream`. A 503 `db_unavailable` means Postgres is unreachable (`systemctl status postgresql`). A missing `REVISION` shows `"sha":"dev"`, so redo the release block. Wrong sha after a release means pm2 is still running the old build: `pm2 restart nowfocus-api --update-env`.
