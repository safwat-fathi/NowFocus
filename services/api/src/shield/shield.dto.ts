import { Type } from 'class-transformer';
import { ArrayMaxSize, ArrayMinSize, IsArray, IsIn, IsOptional, IsString, Length, ValidateNested } from 'class-validator';
import { PLATFORMS } from '../db/entities.js';

export class ShieldItemDto {
  @IsIn(['domain', 'application', 'category']) targetType!: 'domain' | 'application' | 'category';
  @IsString() @Length(1, 253) targetValue!: string;
  @IsOptional() @IsString() @Length(1, 100) displayName?: string;
  @IsOptional() @IsIn(['all', ...PLATFORMS]) platform?: 'all' | (typeof PLATFORMS)[number];
}

export class AddItemsDto {
  @IsArray() @ArrayMinSize(1) @ArrayMaxSize(50) @ValidateNested({ each: true }) @Type(() => ShieldItemDto) items!: ShieldItemDto[];
}
