import { useState } from "react";
import { open } from "@tauri-apps/plugin-dialog";
import { api } from "../lib/api";
import type { AppState, PolicyMode, Profile, RuleGroup } from "../types";
import { profileSummary } from "../lib/profile";
import { errorText, tOr, useI18n, type T } from "../i18n";
import { GlobeIcon, PlusIcon, RemoveIcon } from "../components/Icons";

export function Profiles({ state, onState }: { state: AppState; onState: (s: AppState) => void }) {
  const { t } = useI18n();
  const [editId, setEditId] = useState(state.profiles[0]?.id ?? "");
  const profile = state.profiles.find((p) => p.id === editId) ?? state.profiles[0];

  // The mode is chosen here and never changes: the same rows mean opposite things in the two modes.
  const [choosing, setChoosing] = useState(false);

  async function addProfile(mode: PolicyMode) {
    setChoosing(false);
    const next = await api.createProfile(mode === "allowlist" ? "New whitelist" : "New profile", mode);
    onState(next);
    const created = next.profiles[next.profiles.length - 1];
    if (created) setEditId(created.id);
  }

  return (
    <div className="profiles-grid">
      <div className="profile-list">
        <div className="profile-list__header">
          <span className="screen-title" style={{ fontSize: 26 }}>{t("prof.title")}</span>
          <button className="btn btn-icon" onClick={() => setChoosing(!choosing)} title={t("prof.new")} style={{ width: 36, height: 36 }}>
            <PlusIcon size={18} />
          </button>
        </div>
        {choosing && (
          <div style={{ padding: "0 20px 12px", display: "flex", flexDirection: "column", gap: 8 }}>
            <button className="btn btn-secondary" onClick={() => addProfile("blocklist")} style={{ minHeight: 40, justifyContent: "flex-start" }}>
              {t("prof.blockThese")}
            </button>
            <button className="btn btn-secondary" onClick={() => addProfile("allowlist")} style={{ minHeight: 40, justifyContent: "flex-start" }}>
              {t("prof.allowOnly")}
            </button>
            <p style={{ fontSize: 12, color: "var(--color-neutral-700)", margin: 0 }}>
              {t("prof.modeNote")}
            </p>
          </div>
        )}
        {state.profiles.map((p) => (
          <button key={p.id} className="profile-list-item" data-active={p.id === profile?.id} onClick={() => setEditId(p.id)}>
            <span className="profile-list-item__name">{p.name}</span>
            <span className="profile-list-item__meta">{profileSummary(p, t)}</span>
          </button>
        ))}
        <GroupsPanel groups={state.groups} onState={onState} />
        <p style={{ fontSize: 12, color: "var(--color-neutral-700)", padding: "16px 20px", margin: "auto 0 0" }}>
          {t("prof.thisPcOnly")}
        </p>
      </div>

      {profile ? <ProfileEditor profile={profile} groups={state.groups} onState={onState} /> : (
        <div style={{ padding: 32 }}>{t("prof.createFirst")}</div>
      )}
    </div>
  );
}

function ProfileEditor({ profile, groups, onState }: { profile: Profile; groups: RuleGroup[]; onState: (s: AppState) => void }) {
  const { t } = useI18n();
  const [name, setName] = useState(profile.name);
  const [newDomain, setNewDomain] = useState("");
  const [domainError, setDomainError] = useState("");
  const [appError, setAppError] = useState("");
  const allow = profile.mode === "allowlist";

  async function saveName() {
    if (name.trim() && name !== profile.name) onState(await api.renameProfile(profile.id, name.trim()));
  }

  async function addDomain() {
    if (!newDomain.trim()) return;
    try {
      const next = await api.addDomain(profile.id, newDomain.trim());
      onState(next);
      setNewDomain("");
      setDomainError("");
    } catch (e) {
      setDomainError(errorText(e, t));
    }
  }

  async function pickApplication() {
    const picked = await open({ multiple: false, title: allow ? t("prof.chooseAllow") : t("prof.chooseBlock") });
    if (!picked || Array.isArray(picked)) return;
    const path = picked;
    const name = path.split(/[\\/]/).pop() ?? path;
    await run(() => api.addApplication(profile.id, path, name));
  }

  /** A running session refuses some edits (a blocklist can't shrink, a whitelist can't grow): say why instead of failing silently. */
  async function run(change: () => Promise<AppState>) {
    try {
      onState(await change());
      setAppError("");
    } catch (e) {
      setAppError(errorText(e, t));
    }
  }

  return (
    <div className="profile-editor">
      <div className="profile-editor__name-row">
        <div className="field" style={{ flex: 1, margin: 0 }}>
          <label>{t("prof.name")}</label>
          <input
            className="input"
            value={name}
            onChange={(e) => setName(e.target.value)}
            onBlur={saveName}
            style={{ minHeight: 46, fontSize: 18, fontWeight: 600 }}
          />
        </div>
      </div>

      <div className="profile-editor__columns">
        {!allow && <div style={{ display: "flex", flexDirection: "column" }}>
          <div className="column-header">
            <span className="column-header__label">{t("prof.blockedSites")}</span>
            <span className="column-header__hint">{t("prof.hostsHint")}</span>
          </div>
          <div className="add-row">
            <input
              className="input"
              value={newDomain}
              onChange={(e) => { setNewDomain(e.target.value); setDomainError(""); }}
              onKeyDown={(e) => e.key === "Enter" && addDomain()}
              placeholder={t("prof.addSiteHint")}
              style={{ minHeight: 42, fontSize: 14, flex: 1 }}
            />
            <button className="btn btn-primary" onClick={addDomain} disabled={!newDomain.trim()} style={{ minHeight: 42, padding: "0 16px" }}>
              {t("common.add")}
            </button>
          </div>
          <div className="error-line">{domainError}</div>
          <div className="rule-list">
            {profile.domains.map((d) => (
              <div className="rule-row" key={d.id}>
                <GlobeIcon />
                <span className="rule-row__label">{d.domain}</span>
                <span className="rule-row__hint">{t("prof.subdomains")}</span>
                <button
                  className="btn btn-icon"
                  onClick={async () => onState(await api.removeDomain(profile.id, d.id))}
                  title={t("common.remove")}
                  style={{ width: 34, height: 34, color: "var(--color-accent-700)" }}
                >
                  <RemoveIcon />
                </button>
              </div>
            ))}
          </div>
        </div>}

        <div style={{ display: "flex", flexDirection: "column" }}>
          <div className="column-header">
            <span className="column-header__label">{allow ? t("prof.allowedApps") : t("prof.blockedApps")}</span>
            <span className="column-header__hint">{allow ? t("prof.allowedHint") : t("prof.closedHint")}</span>
          </div>
          {allow && (
            <p style={{ fontSize: 12, color: "var(--color-neutral-700)", margin: "0 0 10px" }}>
              {t("prof.allowNote")}
            </p>
          )}
          <div className="rule-list">
            {profile.applications.map((a) => (
              <div className="app-row" key={a.id}>
                <span className="app-row__badge">{a.displayName[0]?.toUpperCase()}</span>
                <span style={{ flex: 1, minWidth: 0 }}>
                  <span className="app-row__name">{a.displayName}</span>
                  <span className="app-row__id">{a.nativeIdentifier}</span>
                </span>
                <button
                  className="btn btn-icon"
                  onClick={() => run(() => api.removeApplication(profile.id, a.id))}
                  title={t("common.remove")}
                  style={{ width: 34, height: 34, color: "var(--color-accent-700)" }}
                >
                  <RemoveIcon />
                </button>
              </div>
            ))}
          </div>
          <button className="btn btn-secondary" onClick={pickApplication} style={{ minHeight: 40, justifyContent: "flex-start", marginTop: 10, gap: 8, alignSelf: "flex-start" }}>
            <PlusIcon />
            {t("prof.addApp")}
          </button>
          <div style={{ display: "flex", gap: 8, marginTop: 8, flexWrap: "wrap" }}>
            {groups.length > 0 && (
              <select
                className="input"
                value=""
                onChange={(e) => e.target.value && run(() => api.applyGroup(profile.id, e.target.value))}
                style={{ minHeight: 40, fontSize: 14, width: "auto" }}
              >
                <option value="">{t("groups.add")}</option>
                {groups.map((g) => <option key={g.id} value={g.id}>{g.name} · {groupSummary(g, t)}</option>)}
              </select>
            )}
            {(profile.domains.length > 0 || profile.applications.length > 0) && (
              <button className="btn btn-secondary" style={{ minHeight: 40 }} onClick={() => run(() => api.saveGroupFromProfile(profile.id))}>
                {t("groups.saveAs")}
              </button>
            )}
          </div>

          <div className="column-header" style={{ marginTop: 22 }}>
            <span className="column-header__label">{t("prof.feedsTitle")}</span>
            <span className="column-header__hint">{t("prof.feedsHint")}</span>
          </div>
          <div className="rule-list" style={{ marginTop: 6 }}>
            {profile.feeds.map((f) => (
              <button
                key={f.feedKey}
                className="feed-toggle"
                data-on={f.enabled}
                onClick={() => run(() => api.toggleFeed(profile.id, f.feedKey))}
              >
                <span style={{ flex: 1 }}>
                  <span className="feed-toggle__label">{tOr(t, `feed.${f.feedKey}`, f.label)}</span>
                  <span className="feed-toggle__sub">{tOr(t, `feed.${f.feedKey}.sub`, f.sub)}</span>
                </span>
                <span className="switch">
                  <span className="switch__knob" />
                </span>
              </button>
            ))}
          </div>
          <p style={{ fontSize: 11, color: "var(--color-neutral-700)", margin: "8px 0 0" }}>{t("prof.feedsNote")}</p>
          <div className="error-line">{appError}</div>
        </div>
      </div>
    </div>
  );
}

function groupSummary(g: RuleGroup, t: T): string {
  return t("prof.summary", { sites: t("count.sites", { count: g.domains.length }), apps: t("count.apps", { count: g.applications.length }) });
}

/** Saved groups: rename, trim or delete here; "Save as group" in a profile makes a new one. */
function GroupsPanel({ groups, onState }: { groups: RuleGroup[]; onState: (s: AppState) => void }) {
  const { t } = useI18n();
  const [open, setOpen] = useState<string | null>(null);
  const save = async (g: RuleGroup) => onState(await api.updateGroup(g));

  return (
    <div style={{ padding: "12px 20px" }}>
      <span className="column-header__label">{t("groups.title")}</span>
      <p style={{ fontSize: 12, color: "var(--color-neutral-700)", margin: "4px 0 8px" }}>
        {groups.length === 0 ? t("groups.none") : t("groups.note")}
      </p>
      {groups.map((g) => (
        <div key={g.id} style={{ marginBottom: 6 }}>
          <button className="profile-list-item" data-active={open === g.id} onClick={() => setOpen(open === g.id ? null : g.id)}>
            <span className="profile-list-item__name">{g.name}</span>
            <span className="profile-list-item__meta">{groupSummary(g, t)}</span>
          </button>
          {open === g.id && (
            <div style={{ padding: "6px 0 6px 8px" }}>
              <input
                className="input"
                aria-label={t("groups.name")}
                defaultValue={g.name}
                onBlur={(e) => e.target.value.trim() && e.target.value !== g.name && save({ ...g, name: e.target.value.trim() })}
                style={{ minHeight: 36, fontSize: 14, marginBottom: 6 }}
              />
              {g.domains.map((d) => (
                <div className="rule-row" key={d}>
                  <GlobeIcon />
                  <span className="rule-row__label">{d}</span>
                  <button className="btn btn-icon" title={t("common.remove")} onClick={() => save({ ...g, domains: g.domains.filter((x) => x !== d) })} style={{ width: 30, height: 30 }}>
                    <RemoveIcon />
                  </button>
                </div>
              ))}
              {g.applications.map((a) => (
                <div className="rule-row" key={a.nativeIdentifier}>
                  <span className="rule-row__label">{a.displayName}</span>
                  <button className="btn btn-icon" title={t("common.remove")} onClick={() => save({ ...g, applications: g.applications.filter((x) => x.nativeIdentifier !== a.nativeIdentifier) })} style={{ width: 30, height: 30 }}>
                    <RemoveIcon />
                  </button>
                </div>
              ))}
              <button className="btn btn-secondary" style={{ minHeight: 34, marginTop: 6 }} onClick={async () => onState(await api.deleteGroup(g.id))}>
                {t("common.remove")}
              </button>
            </div>
          )}
        </div>
      ))}
    </div>
  );
}
