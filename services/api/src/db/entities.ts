import { Column, CreateDateColumn, Entity, PrimaryColumn } from 'typeorm';

export const PLATFORMS = ['macos', 'windows', 'android', 'ios'] as const;
export type Platform = (typeof PLATFORMS)[number];

@Entity('users')
export class User {
  @PrimaryColumn('uuid') id!: string;
  @Column({ type: 'text' }) email!: string;
  @Column({ type: 'text' }) passwordHash!: string;
  @Column({ type: 'bigint', default: 0 }) syncSeq!: string; // pg returns bigint as string
  @CreateDateColumn({ type: 'timestamptz' }) createdAt!: Date;
}

@Entity('devices')
export class Device {
  @PrimaryColumn('uuid') id!: string;
  @Column({ type: 'uuid' }) userId!: string;
  @Column({ type: 'text' }) name!: string;
  @Column({ type: 'text' }) platform!: Platform;
  @Column({ type: 'text', nullable: true }) refreshHash!: string | null;
  @Column({ type: 'timestamptz', nullable: true }) refreshExpiresAt!: Date | null;
  @CreateDateColumn({ type: 'timestamptz' }) createdAt!: Date;
  @Column({ type: 'timestamptz', nullable: true }) lastSeenAt!: Date | null;
  @Column({ type: 'timestamptz', nullable: true }) revokedAt!: Date | null;
}
