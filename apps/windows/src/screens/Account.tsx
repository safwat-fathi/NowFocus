import { useEffect, useState } from "react";
import { ask } from "@tauri-apps/plugin-dialog";
import { api } from "../lib/api";
import type { AppState, DeviceInfo } from "../types";

function ago(iso: string | null): string {
  if (!iso) return "";
  const s = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 1000));
  if (s < 60) return "just now";
  if (s < 3600) return `${Math.floor(s / 60)} min ago`;
  return `${Math.floor(s / 3600)} h ago`;
}

/** Optional account: sign in to keep profiles and bedtime in sync, and to start a session on one device and have
 * the others join it. People, stats and history never leave this PC. Signed out, nothing here contacts a server. */
export function Account({ state, onState }: { state: AppState; onState: (s: AppState) => void }) {
  const sync = state.sync;
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [devices, setDevices] = useState<DeviceInfo[] | null>(null);
  const [reload, setReload] = useState(0);
  const [deleting, setDeleting] = useState(false);
  const [deletePassword, setDeletePassword] = useState("");

  useEffect(() => {
    if (!sync.signedIn) {
      setDevices(null);
      return;
    }
    api.syncDevices().then(setDevices).catch(() => setDevices(null));
  }, [sync.signedIn, sync.lastSyncedAt, reload]);

  async function submit(create: boolean) {
    if (!email.includes("@")) return setError("Enter a valid email address.");
    if (password.length < 8) return setError("Password must be at least 8 characters.");
    setBusy(true);
    setError("");
    try {
      onState(await api.syncSignIn(email, password, create));
      setPassword("");
    } catch (e) {
      setError(String(e));
    } finally {
      setBusy(false);
    }
  }

  async function signOut() {
    onState(await api.syncSignOut());
  }

  async function deleteAccount() {
    const yes = await ask("This permanently deletes your account and everything synced to it, on every device. Nothing on this PC is deleted, and blocking is unaffected.", {
      title: "Delete account?", kind: "warning", okLabel: "Delete", cancelLabel: "Cancel",
    });
    if (!yes) return;
    try {
      onState(await api.syncDeleteAccount(deletePassword));
      setError("");
      setDeleting(false);
      setDeletePassword("");
    } catch (e) {
      setError(String(e));
    }
  }

  return (
    <div style={{ marginTop: 32, maxWidth: 520 }}>
      <div className="field-label">Account</div>
      {!sync.signedIn ? (
        <>
          <p style={{ fontSize: 14, color: "var(--color-neutral-800)" }}>
            Optional. Sign in to keep your profiles and bedtime settings in sync, and to start a session on one device and
            have your others join it. Stats, history and what you browse stay on this PC, and NowFocus keeps blocking from
            this PC's own copy even when you're offline.
          </p>
          {sync.problem && <div className="error-line">{sync.problem}</div>}
          <div className="field">
            <label>Email</label>
            <input className="input" type="email" value={email} onChange={(e) => setEmail(e.target.value)} />
          </div>
          <div className="field">
            <label>Password (8+ characters)</label>
            <input className="input" type="password" value={password} onChange={(e) => setPassword(e.target.value)} />
          </div>
          {error && <div className="error-line">{error}</div>}
          <div style={{ display: "flex", gap: 10, marginTop: 12 }}>
            <button className="btn btn-primary" disabled={busy} onClick={() => submit(false)} style={{ minHeight: 48 }}>
              {busy ? "Please wait…" : "Sign in"}
            </button>
            <button className="btn btn-secondary" disabled={busy} onClick={() => submit(true)} style={{ minHeight: 48 }}>
              Create account
            </button>
          </div>
        </>
      ) : (
        <>
          <div style={{ fontWeight: 600, fontSize: 17 }}>{sync.email}</div>
          <div style={{ fontSize: 13, color: sync.problem ? "var(--color-accent-700)" : "var(--color-neutral-700)", marginTop: 4 }}>
            {sync.syncing ? "Syncing…" : sync.problem ?? (sync.lastSyncedAt ? `Synced ${ago(sync.lastSyncedAt)}` : "Waiting to sync")}
          </div>
          {sync.rejected > 0 && (
            <div style={{ fontSize: 13, color: "var(--color-accent-700)", marginTop: 4 }}>
              {sync.rejected} change{sync.rejected === 1 ? "" : "s"} couldn't be synced. They stay on this PC; editing them again retries.
            </div>
          )}
          <div style={{ display: "flex", gap: 10, marginTop: 12 }}>
            <button className="btn btn-secondary" onClick={async () => onState(await api.syncNow())} style={{ minHeight: 40 }}>Sync now</button>
          </div>
          <label style={{ display: "flex", alignItems: "flex-start", gap: 10, marginTop: 16, fontSize: 14 }}>
            <input type="checkbox" checked={sync.joinRemote} onChange={async (e) => onState(await api.syncSetJoinRemote(e.target.checked))} style={{ marginTop: 3 }} />
            <span>
              Join sessions from my other devices
              <br />
              <span style={{ fontSize: 12, color: "var(--color-neutral-700)" }}>
                A session you start on another device starts here too, up to 24 hours. Turn off to keep this PC separate.
              </span>
            </span>
          </label>

          <div className="field-label" style={{ marginTop: 22 }}>Devices on this account</div>
          {devices === null && <div style={{ fontSize: 13 }}>Loading…</div>}
          {devices?.filter((d) => !d.revoked).map((d) => (
            <div key={d.id} style={{ display: "flex", alignItems: "center", padding: "8px 0", borderBottom: "1px solid var(--color-divider)" }}>
              <div style={{ flex: 1 }}>
                <div style={{ fontWeight: 600 }}>{d.name}</div>
                <div style={{ fontSize: 12, color: "var(--color-neutral-700)" }}>{d.platform}</div>
              </div>
              {d.current ? (
                <span className="tag tag-neutral">This PC</span>
              ) : (
                <button className="btn" onClick={async () => { await api.syncRevokeDevice(d.id).catch((e) => setError(String(e))); setReload((n) => n + 1); }}>
                  Remove
                </button>
              )}
            </div>
          ))}

          <p style={{ fontSize: 13, color: "var(--color-neutral-800)", marginTop: 22 }}>
            Signing out keeps your profiles, bedtime and Commitment on this PC. Blocking continues as before.
          </p>
          <div style={{ display: "flex", gap: 10 }}>
            <button className="btn btn-secondary" onClick={signOut} style={{ minHeight: 44 }}>Sign out</button>
            <button className="btn" onClick={() => setDeleting(true)} style={{ minHeight: 44 }}>Delete account…</button>
          </div>
          {deleting && (
            <div style={{ marginTop: 14 }}>
              <div className="field">
                <label>Confirm with your password</label>
                <input className="input" type="password" value={deletePassword} onChange={(e) => setDeletePassword(e.target.value)} />
              </div>
              <div style={{ display: "flex", gap: 10 }}>
                <button className="btn btn-primary" disabled={!deletePassword} onClick={deleteAccount} style={{ minHeight: 44 }}>Delete account</button>
                <button className="btn btn-secondary" onClick={() => { setDeleting(false); setDeletePassword(""); }} style={{ minHeight: 44 }}>Cancel</button>
              </div>
            </div>
          )}
          {error && <div className="error-line">{error}</div>}
        </>
      )}
    </div>
  );
}
