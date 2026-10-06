import { ApiProperty } from '@nestjs/swagger';
import { Type } from 'class-transformer';
import { ArrayMaxSize, IsArray, IsInt, IsOptional, Max, Min } from 'class-validator';
import { WRITABLE_TYPES, type WritableType } from './validate.js';

/** One entity change. Only `policy`, `session`, `bedtime_settings` and `user_settings` are writable. */
export class PushChange {
  // Explicit enum: the plugin infers it from the union, whose order TypeScript changes with unrelated edits.
  @ApiProperty({ enum: WRITABLE_TYPES })
  type!: WritableType;
  /** UUID for policy/session (any case; stored lowercase); `default` for the settings types. */
  id!: string;
  /** ISO 8601 with timezone. Last-write-wins compares this; values >5 min in the future are clamped. */
  updatedAt!: string;
  /** Entity payload; validated only for the fields the server enforces, the rest is stored verbatim. */
  data!: Record<string, any>;
  /** Only policies can be deleted (tombstone). */
  deleted?: boolean;
}

export class PushDto {
  // Items are validated one by one in the service so a single bad change never fails the batch.
  @ApiProperty({ type: () => [PushChange], maxItems: 100 })
  @IsArray() @ArrayMaxSize(100) changes!: unknown[];
}

export class PullQuery {
  /** Return changes with seq greater than this. 0 = everything. */
  @IsOptional() @Type(() => Number) @IsInt() @Min(0) cursor?: number;
  /** Page size, default 500. */
  @IsOptional() @Type(() => Number) @IsInt() @Min(1) @Max(1000) limit?: number;
}
