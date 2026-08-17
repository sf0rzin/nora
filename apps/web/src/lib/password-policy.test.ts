/**
 * `src/lib/password-policy.ts` is seven lines and two constants, and asserting that 10 is 10
 * would be worthless. What is worth asserting is the thing the module exists for: those two
 * numbers are a COPY of the backend's, and nothing in the build fails when the copy drifts.
 *
 * So this file reads the Java and compares. The sources of truth, all in
 * `services/api/src/main/java/br/com/nora/api/`:
 *
 *   domain/identity/PasswordPolicy.java     MIN_LENGTH / MAX_LENGTH  — the runtime check
 *   api/dto/auth/SignupRequest.java         @Size(min = 10, max = 128)
 *   api/dto/auth/ConfirmPasswordResetRequest.java
 *   api/dto/iam/AcceptInviteRequest.java
 *
 * All four have to agree, and the front end has to agree with all four. If they do not, a user
 * types a password the browser accepts and the server rejects — and the three screens that
 * import these constants (signup, password reset, invite acceptance) show a rule the API does
 * not enforce.
 *
 * Reading the Java rather than restating it is deliberate: a test that hardcodes 10 and 128 on
 * both sides passes forever after someone changes the backend, which is the exact failure this
 * file is here to catch.
 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

import { PASSWORD_MAX, PASSWORD_MIN } from '@/lib/password-policy';

const API_JAVA_ROOT = new URL(
  '../../../../services/api/src/main/java/br/com/nora/api/',
  import.meta.url,
);

/**
 * Reads a backend source file. Failing loudly when it is missing is the point: a mirror test
 * that skips itself when it cannot find the other half of the mirror provides nothing while
 * reporting green, which is worse than not existing. The whole repository is checked out both
 * in CI and on a workstation, so absence means the path moved — a finding, not a reason to
 * stay quiet.
 */
function readApiSource(relativePath: string): string {
  const path = fileURLToPath(new URL(relativePath, API_JAVA_ROOT));
  try {
    return readFileSync(path, 'utf8');
  } catch (cause) {
    throw new Error(
      `Cannot read the backend source this test mirrors: ${path}. ` +
        'If the file moved, update this test; do not delete the assertion.',
      { cause },
    );
  }
}

/** Pulls `public static final int <name> = <number>;` out of a Java source. */
function javaIntConstant(source: string, name: string): number {
  const match = new RegExp(`static final int ${name}\\s*=\\s*(\\d+)\\s*;`).exec(source);
  if (!match) throw new Error(`Constant ${name} not found in the backend source.`);
  return Number(match[1]);
}

/** Pulls every `@Size(min = N, max = M)` from a Java source, in declaration order. */
function javaSizeAnnotations(source: string): Array<{ min?: number; max: number }> {
  return [...source.matchAll(/@Size\(\s*(?:min\s*=\s*(\d+)\s*,\s*)?max\s*=\s*(\d+)\s*\)/g)].map(
    (m) => ({
      min: m[1] === undefined ? undefined : Number(m[1]),
      max: Number(m[2]),
    }),
  );
}

describe('password policy mirrors the backend', () => {
  it('matches PasswordPolicy.MIN_LENGTH / MAX_LENGTH', () => {
    const source = readApiSource('domain/identity/PasswordPolicy.java');
    expect(PASSWORD_MIN).toBe(javaIntConstant(source, 'MIN_LENGTH'));
    expect(PASSWORD_MAX).toBe(javaIntConstant(source, 'MAX_LENGTH'));
  });

  it.each([
    ['api/dto/auth/SignupRequest.java'],
    ['api/dto/auth/ConfirmPasswordResetRequest.java'],
    ['api/dto/iam/AcceptInviteRequest.java'],
  ])('matches the @Size bean-validation bound in %s', (relativePath) => {
    // Each of these DTOs carries exactly one password field with a min AND a max; the other
    // @Size annotations in the same records (LoginRequest's max-only cap, display names) are
    // not password-policy bounds, so the filter on `min !== undefined` is what keeps this
    // assertion about passwords.
    const bounded = javaSizeAnnotations(readApiSource(relativePath)).filter(
      (s) => s.min !== undefined,
    );
    expect(bounded.length).toBeGreaterThan(0);
    for (const { min, max } of bounded) {
      expect(min).toBe(PASSWORD_MIN);
      expect(max).toBe(PASSWORD_MAX);
    }
  });

  it('keeps the bounds usable: a minimum below the maximum, and a maximum bcrypt tolerates', () => {
    // The 128 cap is not cosmetic — `LoginRequest.java` documents it as DoS protection against
    // prolonged hashing, and bcrypt silently truncates at 72 bytes, so a cap far above that
    // would let a user believe in entropy the algorithm never sees.
    expect(PASSWORD_MIN).toBeLessThan(PASSWORD_MAX);
    expect(PASSWORD_MAX).toBeLessThanOrEqual(128);
  });
});
