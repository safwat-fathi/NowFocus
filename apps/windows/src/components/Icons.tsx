// Inline SVGs transcribed from the fetched NowFocus PC.dc.html so the icon
// set matches the design exactly. A generic path-driven <Icon> wrapper isn't
// worth it for a fixed set of ~15 icons — named components are more direct.

type Props = { size?: number; className?: string };

const base = (size: number) => ({
  width: size,
  height: size,
  viewBox: "0 0 24 24",
  fill: "none" as const,
  stroke: "currentColor",
  strokeWidth: 2,
  className: "svg-icon",
});

export function MinimizeIcon({ size = 12 }: Props) {
  return (
    <svg width={size} height={size} viewBox="0 0 12 12" fill="none" stroke="currentColor" className="svg-icon">
      <path d="M1 6h10" />
    </svg>
  );
}

export function MaximizeIcon({ size = 12 }: Props) {
  return (
    <svg width={size} height={size} viewBox="0 0 12 12" fill="none" stroke="currentColor" className="svg-icon">
      <rect x="1.5" y="1.5" width="9" height="9" />
    </svg>
  );
}

export function CloseIcon({ size = 12 }: Props) {
  return (
    <svg width={size} height={size} viewBox="0 0 12 12" fill="none" stroke="currentColor" className="svg-icon">
      <path d="M1.5 1.5l9 9M10.5 1.5l-9 9" />
    </svg>
  );
}

export function FocusIcon({ size = 18 }: Props) {
  return (
    <svg {...base(size)}>
      <line x1="10" x2="14" y1="2" y2="2" />
      <line x1="12" x2="15" y1="14" y2="11" />
      <circle cx="12" cy="14" r="8" />
    </svg>
  );
}

export function ProfilesIcon({ size = 18 }: Props) {
  return (
    <svg {...base(size)}>
      <path d="M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z" />
    </svg>
  );
}

export function DevicesIcon({ size = 18 }: Props) {
  return (
    <svg {...base(size)}>
      <path d="M18 8V6a2 2 0 0 0-2-2H4a2 2 0 0 0-2 2v7a2 2 0 0 0 2 2h8" />
      <path d="M10 19v-3.96 3.15" />
      <path d="M7 19h5" />
      <rect width="6" height="10" x="16" y="12" rx="2" />
    </svg>
  );
}

export function StatsIcon({ size = 18 }: Props) {
  return (
    <svg {...base(size)}>
      <path d="M3 3v16a2 2 0 0 0 2 2h16" />
      <path d="M18 17V9" />
      <path d="M13 17V5" />
      <path d="M8 17v-3" />
    </svg>
  );
}

export function CommitmentIcon({ size = 18 }: Props) {
  return (
    <svg {...base(size)}>
      <rect width="18" height="11" x="3" y="11" />
      <path d="M7 11V7a5 5 0 0 1 10 0v4" />
    </svg>
  );
}

export function BedtimeIcon({ size = 18 }: Props) {
  return (
    <svg {...base(size)}>
      <path d="M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z" />
    </svg>
  );
}

export function PlusIcon({ size = 15 }: Props) {
  return (
    <svg {...base(size)} strokeWidth={2.5}>
      <path d="M5 12h14" />
      <path d="M12 5v14" />
    </svg>
  );
}

export function RemoveIcon({ size = 15 }: Props) {
  return (
    <svg {...base(size)}>
      <path d="M18 6 6 18" />
      <path d="m6 6 12 12" />
    </svg>
  );
}

export function ArrowRightIcon({ size = 20 }: Props) {
  return (
    <svg {...base(size)} strokeLinecap="square">
      <path d="M5 12h14" />
      <path d="m12 5 7 7-7 7" />
    </svg>
  );
}

export function CheckIcon({ size = 10 }: Props) {
  return (
    <svg {...base(size)} strokeWidth={4}>
      <path d="M20 6 9 17l-5-5" />
    </svg>
  );
}

export function GlobeIcon({ size = 15 }: Props) {
  return (
    <svg {...base(size)} stroke="var(--color-neutral-600)">
      <circle cx="12" cy="12" r="10" />
      <path d="M12 2a14.5 14.5 0 0 0 0 20 14.5 14.5 0 0 0 0-20" />
      <path d="M2 12h20" />
    </svg>
  );
}
