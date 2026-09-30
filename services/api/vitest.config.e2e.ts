import { defineConfig } from 'vitest/config';

// e2e runs against a real Postgres (never mocked). Files share one database, so
// they run serially; test/global-setup.ts rebuilds the schema once.
export default defineConfig({
  test: {
    globals: true,
    root: './',
    include: ['**/*.e2e-spec.ts'],
    globalSetup: ['./test/global-setup.ts'],
    fileParallelism: false,
    env: {
      DATABASE_URL: process.env.DATABASE_URL ?? 'postgres://localhost:5432/nowfocus_test',
      JWT_SECRET: 'test-secret-test-secret-test-secret',
    },
  },
});
