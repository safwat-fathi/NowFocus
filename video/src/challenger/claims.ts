/**
 * Every competitor claim shown on screen, with where it came from. Re-check before publishing: prices change.
 * Fetched 2026-10-03.
 */
export const CLAIMS = {
  opal: {
    name: "Opal Pro",
    price: 19.99,
    per: "mo",
    note: "Hard Mode is Pro-only",
    source: "opalapp.com/pricing",
  },
  freedom: {
    name: "Freedom Premium",
    price: 8.99,
    per: "mo",
    note: "Account required",
    source: "freedom.to/pricing",
  },
  checked: "Oct 2026",
} as const;

export const FOOTNOTE = `Monthly prices from ${CLAIMS.opal.source} and ${CLAIMS.freedom.source}, ${CLAIMS.checked}. Names are trademarks of their owners.`;
