import type { ReactNode } from "react";

/** Says a feature is part of Pro and why it does nothing on this account. Shown only while `state.pro` is false. */
export function ProNote({ children }: { children: ReactNode }) {
  return (
    <p className="pro-note" role="note">
      {children}
    </p>
  );
}
