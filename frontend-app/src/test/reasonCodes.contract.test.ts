// @vitest-environment node
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import {
  CODE_PREFIX,
  ERROR_CODES,
  NO_USER_PERMISSION_DETAILS,
  REASON_CODES,
} from '../types/k8sAutomation';

/**
 * The frontend's reason codes must match the fixture the backend's
 * K8sAutomationReasonContractTest reads (design §6.1, §17 "Contract fixture"). Each side fails
 * when its own list drifts from the file.
 *
 * Limit: the frontend CI job runs only when frontend-app/ changes, so a backend-only change to the
 * enum and the fixture is caught here at the next frontend change. Until then an unknown code
 * renders generically and keeps the control blocked (§6.1), so drift is safe.
 */
// Node environment: under jsdom import.meta.url isn't a file: URL, so node:fs couldn't open it.
const FIXTURE_URL = new URL('../../../xamops-service/src/test/resources/k8s/k8s-reason-codes.json', import.meta.url);

interface ReasonCodeFixture {
  codePrefix: string;
  gates: string[];
  errors: string[];
  noUserPermissionDetails: string[];
}

const fixture = JSON.parse(readFileSync(FIXTURE_URL, 'utf8')) as ReasonCodeFixture;

describe('reason codes match the shared fixture', () => {
  it('gate codes, in the §6.1 order', () => {
    expect([...REASON_CODES]).toEqual(fixture.gates);
  });

  it('error codes', () => {
    expect([...ERROR_CODES]).toEqual(fixture.errors);
  });

  it('NO_USER_PERMISSION details', () => {
    expect([...NO_USER_PERMISSION_DETAILS]).toEqual(fixture.noUserPermissionDetails);
  });

  it('the wire prefix', () => {
    expect(CODE_PREFIX).toBe(fixture.codePrefix);
  });

  it('gates and errors never overlap', () => {
    expect(REASON_CODES.filter((code) => (ERROR_CODES as readonly string[]).includes(code))).toEqual([]);
  });
});
