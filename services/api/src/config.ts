export class Config {
  databaseUrl!: string;
  jwtSecret!: string;
  port!: number;
  /** Interface to bind. Loopback by default: the reverse proxy is the only public entry point. */
  host!: string;
  /** How many proxy hops to trust for `req.ip` (see setup.ts). 1 = nginx, which overwrites X-Forwarded-For. */
  trustProxy!: number;
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
  return {
    databaseUrl: need('DATABASE_URL'),
    jwtSecret,
    port: Number(env.PORT ?? 3000),
    host: env.HOST ?? '127.0.0.1',
    trustProxy,
  };
}
