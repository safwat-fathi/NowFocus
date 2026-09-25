import { useState } from "react";
import { open } from "@tauri-apps/plugin-dialog";
import { api } from "../lib/api";
import type { AppState, Profile } from "../types";
import { GlobeIcon, PlusIcon, RemoveIcon } from "../components/Icons";

export function Profiles({ state, onState }: { state: AppState; onState: (s: AppState) => void }) {
  const [editId, setEditId] = useState(state.profiles[0]?.id ?? "");
  const profile = state.profiles.find((p) => p.id === editId) ?? state.profiles[0];

  async function addProfile() {
    const next = await api.createProfile("New profile");
    onState(next);
    const created = next.profiles[next.profiles.length - 1];
    if (created) setEditId(created.id);
  }

  return (
    <div className="profiles-grid">
      <div className="profile-list">
        <div className="profile-list__header">
          <span className="screen-title" style={{ fontSize: 26 }}>Profiles</span>
          <button className="btn btn-icon" onClick={addProfile} title="New profile" style={{ width: 36, height: 36 }}>
            <PlusIcon size={18} />
          </button>
        </div>
        {state.profiles.map((p) => (
          <button key={p.id} className="profile-list-item" data-active={p.id === profile?.id} onClick={() => setEditId(p.id)}>
            <span className="profile-list-item__name">{p.name}</span>
            <span className="profile-list-item__meta">
              {p.domains.length} sites · {p.applications.length} apps
            </span>
          </button>
        ))}
        <p style={{ fontSize: 12, color: "var(--color-neutral-700)", padding: "16px 20px", margin: "auto 0 0" }}>
          Profiles apply to this PC only until another device is linked.
        </p>
      </div>

      {profile ? <ProfileEditor profile={profile} onState={onState} /> : (
        <div style={{ padding: 32 }}>Create a profile to get started.</div>
      )}
    </div>
  );
}

function ProfileEditor({ profile, onState }: { profile: Profile; onState: (s: AppState) => void }) {
  const [name, setName] = useState(profile.name);
  const [newDomain, setNewDomain] = useState("");
  const [domainError, setDomainError] = useState("");

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
      setDomainError(String(e));
    }
  }

  async function pickApplication() {
    const picked = await open({ multiple: false, title: "Choose an application to block" });
    if (!picked || Array.isArray(picked)) return;
    const path = picked;
    const name = path.split(/[\\/]/).pop() ?? path;
    onState(await api.addApplication(profile.id, path, name));
  }

  return (
    <div className="profile-editor">
      <div className="profile-editor__name-row">
        <div className="field" style={{ flex: 1, margin: 0 }}>
          <label>Profile name</label>
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
        <div style={{ display: "flex", flexDirection: "column" }}>
          <div className="column-header">
            <span className="column-header__label">Blocked websites</span>
            <span className="column-header__hint">Hosts file, every browser</span>
          </div>
          <div className="add-row">
            <input
              className="input"
              value={newDomain}
              onChange={(e) => { setNewDomain(e.target.value); setDomainError(""); }}
              onKeyDown={(e) => e.key === "Enter" && addDomain()}
              placeholder="Add a site, e.g. youtube.com"
              style={{ minHeight: 42, fontSize: 14, flex: 1 }}
            />
            <button className="btn btn-primary" onClick={addDomain} disabled={!newDomain.trim()} style={{ minHeight: 42, padding: "0 16px" }}>
              Add
            </button>
          </div>
          <div className="error-line">{domainError}</div>
          <div className="rule-list">
            {profile.domains.map((d) => (
              <div className="rule-row" key={d.id}>
                <GlobeIcon />
                <span className="rule-row__label">{d.domain}</span>
                <span className="rule-row__hint">+ subdomains</span>
                <button
                  className="btn btn-icon"
                  onClick={async () => onState(await api.removeDomain(profile.id, d.id))}
                  title="Remove"
                  style={{ width: 34, height: 34, color: "var(--color-accent-700)" }}
                >
                  <RemoveIcon />
                </button>
              </div>
            ))}
          </div>
        </div>

        <div style={{ display: "flex", flexDirection: "column" }}>
          <div className="column-header">
            <span className="column-header__label">Feeds only</span>
            <span className="column-header__hint">Search &amp; work stay open</span>
          </div>
          <div className="rule-list" style={{ marginTop: 6 }}>
            {profile.feeds.map((f) => (
              <button
                key={f.feedKey}
                className="feed-toggle"
                data-on={f.enabled}
                onClick={async () => onState(await api.toggleFeed(profile.id, f.feedKey))}
              >
                <span style={{ flex: 1 }}>
                  <span className="feed-toggle__label">{f.label}</span>
                  <span className="feed-toggle__sub">{f.sub}</span>
                </span>
                <span className="switch">
                  <span className="switch__knob" />
                </span>
              </button>
            ))}
          </div>
          <p style={{ fontSize: 11, color: "var(--color-neutral-700)", margin: "8px 0 0" }}>
            Enforced once the browser extension ships (Phase 5) — stored now, not blocked yet.
          </p>

          <div className="column-header" style={{ marginTop: 22 }}>
            <span className="column-header__label">Blocked apps</span>
            <span className="column-header__hint">Closed on launch</span>
          </div>
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
                  onClick={async () => onState(await api.removeApplication(profile.id, a.id))}
                  title="Remove"
                  style={{ width: 34, height: 34, color: "var(--color-accent-700)" }}
                >
                  <RemoveIcon />
                </button>
              </div>
            ))}
          </div>
          <button className="btn btn-secondary" onClick={pickApplication} style={{ minHeight: 40, justifyContent: "flex-start", marginTop: 10, gap: 8, alignSelf: "flex-start" }}>
            <PlusIcon />
            Add application…
          </button>
        </div>
      </div>
    </div>
  );
}
