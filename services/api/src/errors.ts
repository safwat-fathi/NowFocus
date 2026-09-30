import { HttpException } from '@nestjs/common';

/** Domain error with a stable machine-readable `code` next to the HTTP status. */
export const fail = (status: number, code: string, message: string) =>
  new HttpException({ statusCode: status, code, message }, status);
