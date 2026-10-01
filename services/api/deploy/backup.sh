#!/usr/bin/env bash
# Encrypted Postgres backup, shipped off-box. Run from cron twice a day (see RUNBOOK.md).
#
# Settings come from the environment or from $BACKUP_CONF (default ~/.config/nowfocus-backup.env, KEY=value lines):
#   AGE_RECIPIENT    age public key (age1...). The matching PRIVATE key must not live on this machine.
#   RCLONE_DEST      any rclone destination, e.g. r2:nowfocus-backups/db (a plain directory works too)
#   HEALTHCHECK_URL  optional dead-man switch (healthchecks.io): pinged on success, "<url>/fail" on failure
#   DATABASE_URL     optional; defaults to the one in ../.env
#   BACKUP_DIR (default ~/backups/nowfocus), KEEP_LOCAL_DAYS (7), KEEP_REMOTE_DAYS (30)
set -Eeuo pipefail
export PATH="/usr/local/bin:/usr/bin:/bin:$PATH"

conf="${BACKUP_CONF:-$HOME/.config/nowfocus-backup.env}"
if [ -r "$conf" ]; then set -a; . "$conf"; set +a; fi

ping_hc() { [ -z "${HEALTHCHECK_URL:-}" ] || curl -fsS -m 10 --retry 3 -o /dev/null "$HEALTHCHECK_URL$1" || true; }
die() { echo "backup: $*" >&2; ping_hc /fail; exit 1; }
tmp=""
cleanup() { [ -z "$tmp" ] || rm -f "$tmp"; }
trap cleanup EXIT
trap 'ping_hc /fail' ERR

[ -n "${AGE_RECIPIENT:-}" ] || die "AGE_RECIPIENT is required (age public key)"
[ -n "${RCLONE_DEST:-}" ] || die "RCLONE_DEST is required (e.g. r2:nowfocus-backups/db)"
if [ -z "${DATABASE_URL:-}" ]; then
  env_file="$(cd "$(dirname "$0")/.." && pwd)/.env"
  DATABASE_URL=$(sed -n 's/^DATABASE_URL=//p' "$env_file" 2>/dev/null | head -1 | sed -e 's/^["'"'"']//' -e 's/["'"'"']$//')
  [ -n "$DATABASE_URL" ] || die "DATABASE_URL not set and not found in $env_file"
fi

dir="${BACKUP_DIR:-$HOME/backups/nowfocus}"
name="nowfocus-$(date -u +%Y%m%dT%H%M%SZ).dump.age"
mkdir -p "$dir"
tmp="$dir/.$name.part"

# pipefail: a failing pg_dump or age fails the whole script. Staged under a .part name, so a failure never
# leaves something that looks like a finished backup.
# ponytail: the DB password is visible in `ps` for the dump's duration; fine on a single-user box.
pg_dump -Fc --no-owner "$DATABASE_URL" | age -r "$AGE_RECIPIENT" -o "$tmp"
[ "$(wc -c < "$tmp")" -gt 1024 ] || die "backup is suspiciously small, refusing to ship it"
mv "$tmp" "$dir/$name"; tmp=""

rclone copyto "$dir/$name" "$RCLONE_DEST/$name"   # verifies size/checksum after upload

# Prune only after a fresh backup has landed, so a stalled job can never delete its way to zero.
rclone delete "$RCLONE_DEST" --include 'nowfocus-*.dump.age' --min-age "${KEEP_REMOTE_DAYS:-30}d"
find "$dir" -name 'nowfocus-*.dump.age' -mtime "+${KEEP_LOCAL_DAYS:-7}" -delete

ping_hc ""
echo "ok $name ($(wc -c < "$dir/$name") bytes)"
