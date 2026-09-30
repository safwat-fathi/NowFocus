import { Type } from 'class-transformer';
import { IsEmail, IsIn, IsString, Length, MaxLength, MinLength, ValidateNested } from 'class-validator';
import { PLATFORMS, type Platform } from '../db/entities.js';

export class DeviceInfoDto {
  @IsString() @Length(1, 100) name!: string;
  @IsIn(PLATFORMS) platform!: Platform;
}

export class CredentialsDto {
  @IsEmail() @MaxLength(254) email!: string;
  @IsString() @MinLength(8) @MaxLength(200) password!: string;
  @ValidateNested() @Type(() => DeviceInfoDto) device!: DeviceInfoDto;
}

export class RefreshDto {
  @IsString() @MaxLength(200) refreshToken!: string;
}
