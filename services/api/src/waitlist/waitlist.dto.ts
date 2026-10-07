import { ArrayMaxSize, IsArray, IsEmail, IsIn, IsOptional, IsString, MaxLength } from 'class-validator';

export class WaitlistDto {
  @IsEmail()
  @MaxLength(254)
  email!: string;

  @IsArray()
  @IsOptional()
  @ArrayMaxSize(4)
  @IsIn(['android', 'windows', 'macos', 'ios'], { each: true })
  platforms?: string[];

  @IsString()
  @IsOptional()
  @MaxLength(4000)
  featureRequest?: string;

  @IsIn(['en', 'ar'])
  @IsOptional()
  locale?: 'en' | 'ar';
}
