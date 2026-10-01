const ALLOWED = /^[a-z0-9.-]+$/;

/**
 * Line-for-line port of the `normalize()` all three clients share (apps/macos/NowFocusCore/DomainValidation.swift,
 * apps/android/.../DomainValidation.kt, apps/windows/core/src/domain_validation.rs). A root daemon writes these
 * strings into the hosts file, so this is a trust boundary: a bare lowercase hostname, or null.
 *
 * Deliberately no stricter than the clients (no per-label or length checks): policies are last-write-wins as a
 * whole, so a domain the server rejects but a client accepts would make that policy unpushable from that device.
 */
export function normalizeHostname(raw: string): string | null {
  let d = raw.trim().toLowerCase();
  for (const prefix of ['https://', 'http://']) if (d.startsWith(prefix)) d = d.slice(prefix.length);
  if (d.startsWith('www.')) d = d.slice(4);
  const cut = d.search(/[/:?#]/);
  if (cut >= 0) d = d.slice(0, cut);
  const ok = d !== '' && d.includes('.') && !/^[.-]|[.-]$/.test(d) && ALLOWED.test(d);
  return ok ? d : null;
}
