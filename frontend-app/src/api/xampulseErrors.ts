import type { XpErrorCode } from '../types/xampulse';

export class XpApiError extends Error {
  readonly code: XpErrorCode | 'XP_UNKNOWN';
  readonly status: number;
  readonly requestId: string;
  readonly details?: Record<string, unknown>;
  constructor(code: XpErrorCode | 'XP_UNKNOWN', message: string, status: number, requestId = '', details?: Record<string, unknown>) {
    super(message);
    this.name = 'XpApiError';
    this.code = code;
    this.status = status;
    this.requestId = requestId;
    this.details = details;
  }
}
