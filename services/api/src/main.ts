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
  SwaggerModule.setup('docs', app, () => buildDocument(app), { jsonDocumentUrl: 'openapi.json' });
  await app.listen(config.port);
}
await bootstrap();
