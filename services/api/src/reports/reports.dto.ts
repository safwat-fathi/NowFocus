import { IsIn, IsOptional, IsString, MaxLength, MinLength } from 'class-validator';

export class ReportDto {
  @IsString()
  @MinLength(1)
  @MaxLength(4000)
  message!: string;

  @IsString()
  @IsOptional()
  @MaxLength(254)
  contact?: string;

  @IsIn(['android', 'windows', 'macos'])
  platform!: string;

  @IsString()
  @MaxLength(64)
  appVersion!: string;

  @IsString()
  @MaxLength(64)
  osVersion!: string;
}
