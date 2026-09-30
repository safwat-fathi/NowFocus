// `npm run openapi` -> writes openapi.json. Nest's preview mode scans routes without connecting to Postgres.
import { writeFileSync } from 'node:fs';
import { NestFactory } from '@nestjs/core';
import { AppModule } from './app.module.js';
import { buildDocument } from './openapi.js';

process.env.DATABASE_URL ??= 'postgres://localhost/openapi';
process.env.JWT_SECRET ??= 'openapi-generation-only-not-a-secret';
const app = await NestFactory.create(AppModule, { preview: true, logger: false });
writeFileSync('openapi.json', JSON.stringify(buildDocument(app), null, 2) + '\n');
await app.close();
