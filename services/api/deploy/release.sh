#!/usr/bin/env bash
# Releases one commit of the API on the VPS. It is the SSH forced command of the CI deploy key (authorized_keys):
#   restrict,command="/abs/path/to/services/api/deploy/release.sh" ssh-ed25519 AAAA... nowfocus-api-deploy
# so the key can do nothing else. CI runs `ssh safwat@host <40-hex sha>`; by hand: `release.sh <sha>`.
# Same steps as RUNBOOK.md "Release". Migrations are additive, so rollback is a manual redeploy of an older sha.
set -Eeuo pipefail

sha="${SSH_ORIGINAL_COMMAND:-${1:-}}"
[[ "$sha" =~ ^[0-9a-f]{40}$ ]] || { echo "release: need a full 40-char commit sha, got '${sha}'" >&2; exit 2; }

# A non-interactive ssh session has a minimal PATH, so pick up node/pnpm/pm2 the way a login shell would.
export PATH="/usr/local/bin:/usr/bin:/bin:$HOME/.local/share/pnpm:$HOME/.local/bin:$PATH"
[ -s "$HOME/.nvm/nvm.sh" ] && . "$HOME/.nvm/nvm.sh"
for c in git node pnpm pm2 flock; do command -v "$c" > /dev/null || { echo "release: $c not on PATH" >&2; exit 3; }; done

repo="$(cd "$(dirname "$0")/../../.." && pwd)"
exec 9> "${TMPDIR:-/tmp}/nowfocus-release.lock"
flock -n 9 || { echo "release: another release is running" >&2; exit 4; }

# One brace group, parsed whole before it runs: the merge below can rewrite this very file, and bash reads scripts lazily.
{
  cd "$repo"
  git fetch --quiet origin main
  git merge --ff-only "$sha"          # never a reset: refuses a sha that isn't ahead of what is deployed

  cd services/api
  pnpm install --frozen-lockfile
  echo "$sha" > REVISION              # gitignored; /healthz reports it
  pnpm run migrate                    # builds, then applies migrations; run from services/api so .env loads
  pm2 restart nowfocus-api --update-env && pm2 save
  echo "release: $sha restarted"
  exit 0
}
