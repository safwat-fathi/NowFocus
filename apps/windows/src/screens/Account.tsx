import { useEffect, useState } from "react";
import { ask } from "@tauri-apps/plugin-dialog";
import { api } from "../lib/api";
import { errorText, useI18n, type T } from "../i18n";
import type { AppState, DeviceInfo } from "../types";

function ago(iso: string | null, t: T): string {
  if (!iso) return "";
  const s = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 1000));
  if (s < 60) return t("ago.now");
  if (s < 3600) return t("ago.min", { n: Math.floor(s / 60) });
  return t("ago.hour", { n: Math.floor(s / 3600) });
}

/** Optional account: sign in to keep profiles and bedtime in sync, and to start a session on one device and have
 * the others join it. People, stats and history never leave this PC. Signed out, nothing here contacts a server. */
export function Account({ state, onState }: { state: AppState; onState: (s: AppState) => void }) {
  const { t } = useI18n();
  const sync = state.sync;
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [devices, setDevices] = useState<DeviceInfo[] | null>(null);
  const [reload, setReload] = useState(0);
  const [notice, setNotice] = useState("");
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
    if (!email.includes("@")) return setError(t("acct.badEmail"));
    if (password.length < 8) return setError(t("acct.shortPassword"));
    setBusy(true);
    setError("");
    setNotice("");
    try {
      if (create) {
        await api.syncCreateAccount(email, password);
        setNotice(t("acct.checkInbox"));
      } else {
        onState(await api.syncSignIn(email, password));
      }
      setPassword("");
    } catch (e) {
      setError(errorText(e, t));
    } finally {
      setBusy(false);
    }
  }

  async function signOut() {
    onState(await api.syncSignOut());
  }

  async function deleteAccount() {
    const yes = await ask(t("acct.deleteBody"), {
      title: t("acct.deleteTitle"), kind: "warning", okLabel: t("common.delete"), cancelLabel: t("common.cancel"),
    });
    if (!yes) return;
    try {
      onState(await api.syncDeleteAccount(deletePassword));
      setError("");
      setDeleting(false);
      setDeletePassword("");
    } catch (e) {
      setError(errorText(e, t));
    }
  }

  return (
    <div style={{ marginTop: 32, maxWidth: 520 }}>
      <div className="field-label">{t("acct.title")}</div>
      {!sync.signedIn ? (
        <>
          <p style={{ fontSize: 14, color: "var(--color-neutral-800)" }}>
            {t("acct.intro")}
          </p>
          {sync.problem && <div className="error-line">{errorText(sync.problem, t)}</div>}
          <div className="field">
            <label>{t("acct.email")}</label>
            <input className="input" type="email" value={email} onChange={(e) => setEmail(e.target.value)} />
          </div>
          <div className="field">
            <label>{t("acct.password")}</label>
            <input className="input" type="password" value={password} onChange={(e) => setPassword(e.target.value)} />
          </div>
          {error && <div className="error-line">{error}</div>}
          {notice && <p style={{ fontSize: 14, color: "var(--color-neutral-800)" }}>{notice}</p>}
          <div style={{ display: "flex", gap: 10, marginTop: 12 }}>
            <button className="btn btn-primary" disabled={busy} onClick={() => submit(false)} style={{ minHeight: 48 }}>
              {busy ? t("common.wait") : t("acct.signIn")}
            </button>
            <button className="btn btn-secondary" disabled={busy} onClick={() => submit(true)} style={{ minHeight: 48 }}>
              {t("acct.create")}
            </button>
          </div>
        </>
      ) : (
        <>
          <div style={{ fontWeight: 600, fontSize: 17 }}>{sync.email}</div>
          <div style={{ fontSize: 13, color: sync.problem ? "var(--color-accent-700)" : "var(--color-neutral-700)", marginTop: 4 }}>
            {sync.syncing ? t("acct.syncing") : sync.problem ? errorText(sync.problem, t) : sync.lastSyncedAt ? t("acct.synced", { when: ago(sync.lastSyncedAt, t) }) : t("acct.waiting")}
          </div>
          {sync.rejected > 0 && (
            <div style={{ fontSize: 13, color: "var(--color-accent-700)", marginTop: 4 }}>
              {t("acct.rejected", { count: sync.rejected })}
            </div>
          )}
          <div style={{ display: "flex", gap: 10, marginTop: 12 }}>
            <button className="btn btn-secondary" onClick={async () => onState(await api.syncNow())} style={{ minHeight: 40 }}>{t("acct.syncNow")}</button>
          </div>
          <label style={{ display: "flex", alignItems: "flex-start", gap: 10, marginTop: 16, fontSize: 14 }}>
            <input type="checkbox" checked={sync.joinRemote} onChange={async (e) => onState(await api.syncSetJoinRemote(e.target.checked))} style={{ marginTop: 3 }} />
            <span>
              {t("acct.joinTitle")}
              <br />
              <span style={{ fontSize: 12, color: "var(--color-neutral-700)" }}>
                {t("acct.joinSub")}
              </span>
            </span>
          </label>

          <div className="field-label" style={{ marginTop: 22 }}>{t("acct.devicesKicker")}</div>
          {devices === null && <div style={{ fontSize: 13 }}>{t("common.loading")}</div>}
          {devices?.filter((d) => !d.revoked).map((d) => (
            <div key={d.id} style={{ display: "flex", alignItems: "center", padding: "8px 0", borderBottom: "1px solid var(--color-divider)" }}>
              <div style={{ flex: 1 }}>
                <div style={{ fontWeight: 600 }}>{d.name}</div>
                <div style={{ fontSize: 12, color: "var(--color-neutral-700)" }}>{d.platform}</div>
              </div>
              {d.current ? (
                <span className="tag tag-neutral">{t("common.thisPc")}</span>
              ) : (
                <button className="btn" onClick={async () => { await api.syncRevokeDevice(d.id).catch((e) => setError(errorText(e, t))); setReload((n) => n + 1); }}>
                  {t("common.remove")}
                </button>
              )}
            </div>
          ))}

          <p style={{ fontSize: 13, color: "var(--color-neutral-800)", marginTop: 22 }}>
            {t("acct.signOutNote")}
          </p>
          <div style={{ display: "flex", gap: 10 }}>
            <button className="btn btn-secondary" onClick={signOut} style={{ minHeight: 44 }}>{t("acct.signOut")}</button>
            <button className="btn" onClick={() => setDeleting(true)} style={{ minHeight: 44 }}>{t("acct.deleteDots")}</button>
          </div>
          {deleting && (
            <div style={{ marginTop: 14 }}>
              <div className="field">
                <label>{t("acct.confirmPassword")}</label>
                <input className="input" type="password" value={deletePassword} onChange={(e) => setDeletePassword(e.target.value)} />
              </div>
              <div style={{ display: "flex", gap: 10 }}>
                <button className="btn btn-primary" disabled={!deletePassword} onClick={deleteAccount} style={{ minHeight: 44 }}>{t("acct.deleteAccount")}</button>
                <button className="btn btn-secondary" onClick={() => { setDeleting(false); setDeletePassword(""); }} style={{ minHeight: 44 }}>{t("common.cancel")}</button>
              </div>
            </div>
          )}
          {error && <div className="error-line">{error}</div>}
        </>
      )}
    </div>
  );
}
