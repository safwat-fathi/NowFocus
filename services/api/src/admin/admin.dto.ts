import { IsEmail, IsIn, IsISO8601, IsOptional, MaxLength } from 'class-validator';
import { PLANS, type Plan } from '../auth/plan.js';

export class SetPlanDto {
  @IsEmail() @MaxLength(254) email!: string;
  @IsIn(PLANS) plan!: Plan;
  /** Pro lapses at this instant (ISO 8601). Omit for no end date. Ignored when `plan` is free. */
  @IsOptional() @IsISO8601() until?: string;
}
