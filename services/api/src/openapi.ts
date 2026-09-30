import type { INestApplication } from '@nestjs/common';
import { DocumentBuilder, SwaggerModule } from '@nestjs/swagger';

export function buildDocument(app: INestApplication) {
  const config = new DocumentBuilder()
    .setTitle('NowFocus Sync API')
    .setDescription(
      'Cross-device sync for NowFocus. Local-first: apps keep enforcing offline and use push/pull to converge. ' +
        'Wire rules: ids are lowercase UUIDs, timestamps ISO 8601 UTC, JSON camelCase. Application rules carry an ' +
        'opaque per-platform nativeIdentifier the server never interprets.',
    )
    .setVersion('1.0')
    .setOpenAPIVersion('3.1.0')
    .addBearerAuth()
    .build();
  return SwaggerModule.createDocument(app, config);
}
