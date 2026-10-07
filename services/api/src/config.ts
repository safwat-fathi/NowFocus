export class Config {
  databaseUrl!: string;
  jwtSecret!: string;
  port!: number;
  /** Interface to bind. Loopback by default: the reverse proxy is the only public entry point. */
  host!: string;
  /** How many proxy hops to trust for `req.ip` (see setup.ts). 1 = nginx, which overwrites X-Forwarded-For. */
  trustProxy!: number;
  githubToken?: string;
  githubRepo?: string;
  /** Brevo v3 API key. Unset = no email is sent. */
  brevoApiKey?: string;
  /** Verified Brevo sender; also the Reply-To. */
  mailFrom: string;
  /** Brevo list that waitlist signups are added to (for the launch blast). */
  brevoListId?: number;
  /** Shared secret for /admin. Unset = admin routes 404. */
  adminToken?: string;
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): Config {
  const need = (k: string) => {
    const v = env[k];
    if (!v) throw new Error(`${k} is required`);
    return v;
  };
  const jwtSecret = need('JWT_SECRET');
  if (jwtSecret.length < 32) throw new Error('JWT_SECRET must be at least 32 characters');
  const trustProxy = Number(env.TRUST_PROXY ?? 1);
  if (!Number.isInteger(trustProxy) || trustProxy < 0) throw new Error('TRUST_PROXY must be a non-negative integer (proxy hops)');
  const adminToken = env.ADMIN_TOKEN || undefined;
  if (adminToken && adminToken.length < 32) throw new Error('ADMIN_TOKEN must be at least 32 characters');
  return {
    databaseUrl: need('DATABASE_URL'),
    jwtSecret,
    port: Number(env.PORT ?? 3000),
    host: env.HOST ?? '127.0.0.1',
    trustProxy,
    githubToken: env.GITHUB_TOKEN,
    githubRepo: env.GITHUB_REPO ?? 'safwat-fathi/NowFocus',
    adminToken,
    brevoApiKey: env.BREVO_API_KEY || undefined,
    mailFrom: env.MAIL_FROM ?? 'hello@nowfocus.online',
    brevoListId: env.BREVO_LIST_ID ? Number(env.BREVO_LIST_ID) : undefined,
  };
}
