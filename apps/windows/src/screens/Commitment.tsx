import { useState } from "react";
import { api } from "../lib/api";
import { errorText, useI18n } from "../i18n";
import type { AppState } from "../types";
import { PlusIcon, RemoveIcon } from "../components/Icons";

const DURATION_SECS = 14 * 24 * 60 * 60;

/** The 14-day Commitment Shield: an always-blocked lock, owned and
 * time-anchored by the Windows service (survives quitting the app). Exactly
 * one 60s grace window to undo after starting; after that the service itself
 * refuses to clear it. Domains-only, matching macOS. */
export function Commitment({ state, onState }: { state: AppState; onState: (s: AppState) => void }) {
  const c = state.commitment;
  if (c) {
    return <ActiveCommitment state={state} onState={onState} />;
  }
  return <SetupCommitment onState={onState} />;
}

function ActiveCommitment({ state, onState }: { state: AppState; onState: (s: AppState) => void }) {
  const { t, fmt } = useI18n();
  const c = state.commitment!;
  const [error, setError] = useState("");
  const days = Math.ceil(c.remainingSecs / 86400);
  const graceLeft = Math.max(0, 60 - (DURATION_SECS - c.remainingSecs));

  async function undo() {
    try {
      setError("");
      onState(await api.clearCommitment());
    } catch (e) {
      setError(errorText(e, t));
    }
  }

  return (
    <div className="screen">
      <div className="screen-header">
        <span className="screen-title">{t("commit.title")}</span>
        <span className={"tag " + (c.canCancelNow ? "tag-accent" : "tag-neutral")}>
          {c.canCancelNow ? t("commit.grace") : t("commit.lockedIn")}
        </span>
      </div>

      <div className="stats-headline" style={{ marginTop: 22 }}>
        <span className="stats-headline__value">{t("unit.d", { n: days, count: days })}</span>
        <span style={{ fontSize: 16, color: "var(--color-neutral-700)" }}>
          {t("commit.daysLeft", { date: fmt.shortDay(c.endAt) })}
        </span>
      </div>

      <div style={{ maxWidth: 480, marginTop: 20 }}>
        <div className="stat-row__label" style={{ marginBottom: 8 }}>{t("commit.blockedFor")}</div>
        {c.domains.map((d) => (
          <div key={d} style={{ padding: "8px 0", borderBottom: "1px solid var(--color-divider)", fontWeight: 600 }}><bdi>{d}</bdi></div>
        ))}
      </div>

      {c.canCancelNow ? (
        <div style={{ marginTop: 24 }}>
          <p style={{ fontSize: 13, color: "var(--color-neutral-700)" }}>
            {t("commit.undoNote", { n: graceLeft })}
          </p>
          <button className="btn btn-secondary" onClick={undo} style={{ marginTop: 12 }}>
            {t("commit.undo", { n: graceLeft })}
          </button>
        </div>
      ) : (
        <p style={{ fontSize: 13, color: "var(--color-neutral-700)", marginTop: 24 }}>
          {t("commit.lockedNote")}
        </p>
      )}
      {error && <p style={{ fontSize: 13, color: "var(--color-accent-700)", marginTop: 12 }}>{error}</p>}
    </div>
  );
}

function SetupCommitment({ onState }: { onState: (s: AppState) => void }) {
  const { t } = useI18n();
  const [domains, setDomains] = useState<string[]>([]);
  const [input, setInput] = useState("");
  const [confirming, setConfirming] = useState(false);
  const [error, setError] = useState("");

  function addDomain() {
    const raw = input.trim().toLowerCase();
    if (!raw) return;
    if (!raw.includes(".")) {
      setError(t("commit.badSite"));
      return;
    }
    if (domains.includes(raw)) {
      setError(t("commit.listed", { site: raw }));
      return;
    }
    setDomains([...domains, raw]);
    setInput("");
    setError("");
  }

  async function start() {
    try {
      setError("");
      onState(await api.startCommitment(domains));
    } catch (e) {
      setError(errorText(e, t));
      setConfirming(false);
    }
  }

  return (
    <div className="screen">
      <div className="screen-header">
        <span className="screen-title">{t("commit.title")}</span>
      </div>
      <p className="screen-lede">
        {t("commit.lede")}
      </p>

      <div style={{ maxWidth: 480, marginTop: 20 }}>
        <div className="field-label">{t("commit.sites")}</div>
        <div style={{ display: "flex", gap: 8 }}>
          <input
            className="input"
            placeholder={t("commit.placeholder")}
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && addDomain()}
            style={{ flex: 1 }}
          />
          <button className="btn btn-secondary" onClick={addDomain} style={{ gap: 6 }}>
            <PlusIcon /> {t("common.add")}
          </button>
        </div>
        {error && <p style={{ fontSize: 13, color: "var(--color-accent-700)", marginTop: 8 }}>{error}</p>}

        {domains.map((d) => (
          <div key={d} style={{ display: "flex", justifyContent: "space-between", alignItems: "center", padding: "8px 0", borderBottom: "1px solid var(--color-divider)" }}>
            <span style={{ fontWeight: 600 }}><bdi>{d}</bdi></span>
            <button className="btn btn-icon" onClick={() => setDomains(domains.filter((x) => x !== d))} title={t("common.remove")}>
              <RemoveIcon />
            </button>
          </div>
        ))}

        {!confirming ? (
          <button
            className="btn btn-primary"
            onClick={() => setConfirming(true)}
            disabled={domains.length === 0}
            style={{ marginTop: 24, width: "100%", minHeight: 52 }}
          >
            {t("commit.start")}
          </button>
        ) : (
          <div style={{ marginTop: 24, padding: 16, border: "2px solid var(--color-accent)", background: "var(--color-accent-100, transparent)" }}>
            <div style={{ fontWeight: 600, marginBottom: 8 }}>{t("commit.sure")}</div>
            <p style={{ fontSize: 13, color: "var(--color-neutral-700)", marginBottom: 16 }}>
              {t("commit.confirm", { sites: t("count.sites", { count: domains.length }) })}
            </p>
            <div style={{ display: "flex", gap: 8 }}>
              <button className="btn btn-primary" onClick={start} style={{ flex: 1 }}>{t("commit.yes")}</button>
              <button className="btn btn-secondary" onClick={() => setConfirming(false)} style={{ flex: 1 }}>{t("common.back")}</button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
