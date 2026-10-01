import { type INestApplication, ValidationPipe } from '@nestjs/common';
import type { NestExpressApplication } from '@nestjs/platform-express';
import { WsAdapter } from '@nestjs/platform-ws';

/** Shared by main.ts and the e2e harness so tests exercise the real pipeline. */
export function configureApp(app: INestApplication) {
  app.useGlobalPipes(new ValidationPipe({ whitelist: true, transform: true }));
  app.useWebSocketAdapter(new WsAdapter(app));
  const express = app as NestExpressApplication;
  express.set('trust proxy', 1); // behind nginx: rate limits key on the real client IP (X-Forwarded-For)
  express.useBodyParser('json', { limit: '1mb' });
}
