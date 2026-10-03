import { type INestApplication, ValidationPipe } from '@nestjs/common';
import type { NestExpressApplication } from '@nestjs/platform-express';
import { WsAdapter } from '@nestjs/platform-ws';
import { Config } from './config.js';

/** Shared by main.ts and the e2e harness so tests exercise the real pipeline. */
export function configureApp(app: INestApplication) {
  app.useGlobalPipes(new ValidationPipe({ whitelist: true, transform: true }));
  app.useWebSocketAdapter(new WsAdapter(app));
  const http = app as NestExpressApplication;
  http.useBodyParser('json', { limit: '1mb' });
  // Behind nginx every socket peer is the proxy, so rate limits key on the forwarded client IP (X-Forwarded-For).
  const extraOrigins = (process.env.CORS_ORIGINS ?? '').split(',').map((s) => s.trim()).filter(Boolean); // dev: http://localhost:8080
  http.enableCors({ origin: ['https://nowfocus.online', ...extraOrigins], methods: ['POST'] }); // the waitlist form on the marketing site
  http.set('trust proxy', app.get(Config).trustProxy);
}
