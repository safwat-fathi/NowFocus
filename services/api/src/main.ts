import { NestFactory } from '@nestjs/core';
import { SwaggerModule } from '@nestjs/swagger';
import { AppModule } from './app.module.js';
import { loadConfig } from './config.js';
import { buildDocument } from './openapi.js';
import { configureApp } from './setup.js';

async function bootstrap() {
  try { process.loadEnvFile(); } catch { /* no .env: variables come from the environment */ }
  const config = loadConfig(); // fail fast on missing env
  const app = await NestFactory.create(AppModule);
  configureApp(app);
  app.enableShutdownHooks(); // here, not in configureApp: every e2e app would add process signal listeners
  SwaggerModule.setup('docs', app, () => buildDocument(app), { jsonDocumentUrl: 'openapi.json' });
  await app.listen(config.port, config.host);
}
await bootstrap();
