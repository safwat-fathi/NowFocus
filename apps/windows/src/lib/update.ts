import { useCallback, useEffect, useState } from "react";
import { check, type Update } from "@tauri-apps/plugin-updater";

const RECHECK_MS = 6 * 60 * 60 * 1000;

export interface Updater {
  /** The newer release found by the last check, if any. */
  update: Update | null;
  status: "idle" | "checking" | "upToDate" | "failed" | "installing";
  check: () => Promise<void>;
  /** Downloads and runs the installer; the app exits and the installer restarts it. */
  install: () => Promise<void>;
}

/** Checks the API's update endpoint at launch and every few hours; `check` re-runs it on demand. */
export function useUpdater(): Updater {
  const [update, setUpdate] = useState<Update | null>(null);
  const [status, setStatus] = useState<Updater["status"]>("idle");

  const run = useCallback(async () => {
    setStatus("checking");
    try {
      const found = await check();
      setUpdate(found);
      setStatus(found ? "idle" : "upToDate");
    } catch {
      setStatus("failed");
    }
  }, []);

  useEffect(() => {
    run();
    const id = setInterval(run, RECHECK_MS);
    return () => clearInterval(id);
  }, [run]);

  const install = useCallback(async () => {
    if (!update) return;
    setStatus("installing");
    try {
      await update.downloadAndInstall();
    } catch {
      setStatus("failed");
    }
  }, [update]);

  return { update, status, check: run, install };
}
