import { api, endNormalAfterAsking } from "../lib/api";
import type { AppState } from "../types";
import { ArrowRightIcon } from "../components/Icons";
import { useT } from "../i18n";

/** Full-screen takeover shown when a blocked site/app is opened during a
 * session. In the real (Phase 4) build this is triggered by the Win32
 * foreground hook via `simulate_block`'s same backend path; for now it's
 * only reachable through the Devices/dev "Try a blocked site" affordance
 * used to check this screen against the design (see Shield's call site). */
export function Shield({ state, onState }: { state: AppState; onState: (s: AppState) => void }) {
  const t = useT();
  const shield = state.shield!;
  const session = state.session;
  const isLimit = shield.targetKind === "limit";
  const bedtime = !!session?.bedtime;

  async function backToWork() {
    onState(await api.dismissShield());
  }

  async function usePass() {
    try {
      onState(await api.usePass());
    } catch {
      /* the session ended or no passes are left: the next poll shows the real state */
    }
  }

  async function endEarly() {
    if (!session) return;
    if (session.mode === "normal") {
      const ended = await endNormalAfterAsking(t);
      if (ended) onState(ended);
    } else onState(await api.beginUnlock());
  }

  return (
    <div className="shield-grid">
      <div style={{ display: "flex", flexDirection: "column" }}>
        <div className="shield-kicker">
          <span className="shield-kicker__dot" />
          {/* Who did it (always NowFocus, by name) and to what; the body says which rule. A site is blocked, an app is closed. */}
          {shield.targetKind === "site" ? t("shield.site", { name: shield.targetName }) : t("shield.app", { name: shield.targetName })}
        </div>
        <div style={{ marginTop: "auto" }}>
          <h1 className="shield-title">{t("shield.title", { name: shield.targetName })}</h1>
          <p className="shield-body">
            {isLimit
              ? t("shield.limit", { count: shield.limitMinutes, limitMinutes: shield.limitMinutes, name: shield.targetName })
              : bedtime
                ? t("shield.bedtime", { left: session?.remainingLabel ?? "" })
                : session?.allowlist
                  ? t("shield.allow", { left: session.remainingLabel })
                  : <>{session ? t("shield.session", { profile: session.profileName, left: session.remainingLabel }) : ""} {t("shield.protects")}</>}
          </p>
        </div>
      </div>
      <div className="shield-side">
        <div className="shield-side-list">
          {!isLimit && (
            <div className="shield-side-row">
              <span className="shield-side-row__label">{bedtime ? t("shield.leftBedtime") : t("shield.leftSession")}</span>
              <span className="shield-side-row__value">{session?.remainingLabel ?? "—"}</span>
            </div>
          )}
          <div className="shield-side-row" style={{ borderBottom: "2px solid var(--color-neutral-600)" }}>
            <span className="shield-side-row__label">{t("shield.tries")}</span>
            <span className="shield-side-row__value">{state.stats.blockAttemptsToday}</span>
          </div>
        </div>
        <button className="btn btn-primary" onClick={backToWork} style={{ minHeight: 58, justifyContent: "space-between", fontSize: 17, marginTop: 24 }}>
          {t("shield.back")}
          <ArrowRightIcon size={22} />
        </button>
        {shield.passesLeft > 0 && (
          <button className="btn btn-secondary" onClick={usePass} style={{ minHeight: 48, justifyContent: "flex-start", fontSize: 15, marginTop: 12 }}>
            {t("shield.pass", { name: shield.targetName, n: shield.passesLeft })}
          </button>
        )}
        {shield.passesLeft > 0 && (
          <p style={{ fontSize: 12, color: "var(--color-neutral-400)", margin: "6px 0 0" }}>{t("shield.passNote")}</p>
        )}
        {!isLimit && <button className="btn" onClick={endEarly} style={{ minHeight: 48, justifyContent: "flex-start", color: "var(--color-neutral-300)", fontSize: 15, marginTop: 6, paddingInlineStart: 0 }}>
          {t("shield.need")}
        </button>}
      </div>
    </div>
  );
}
