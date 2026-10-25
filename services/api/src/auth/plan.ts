export const PLANS = ['free', 'pro'] as const;
export type Plan = (typeof PLANS)[number];

/** Pro is the plan plus an optional end date; a null end date never lapses. */
export const isPro = (u: { plan: Plan; planExpiresAt: Date | null }, now: Date) =>
  u.plan === 'pro' && (u.planExpiresAt === null || u.planExpiresAt > now);
