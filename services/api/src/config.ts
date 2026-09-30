export class Config {
  databaseUrl!: string;
  jwtSecret!: string;
  port!: number;
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): Config {
  const need = (k: string) => {
    const v = env[k];
    if (!v) throw new Error(`${k} is required`);
    return v;
  };
  const jwtSecret = need('JWT_SECRET');
  if (jwtSecret.length < 32) throw new Error('JWT_SECRET must be at least 32 characters');
  return { databaseUrl: need('DATABASE_URL'), jwtSecret, port: Number(env.PORT ?? 3000) };
}
