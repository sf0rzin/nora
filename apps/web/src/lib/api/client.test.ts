/**
 * @vitest-environment jsdom
 *
 * `src/lib/api/client.ts` — 66 exported functions, every one of which is a one-line wrapper
 * around a single private `request<T>()`. Testing the 66 would be 66 assertions that
 * `encodeURIComponent` exists; the logic lives in `request`, and `request` is where the
 * untested behaviour is: header assembly, the FormData exception, the 401 interceptor with its
 * one refresh and one retry, the `_isRetry` loop guard, the error-code to pt-BR copy mapping,
 * and the two empty-body shapes.
 *
 * `request` is not exported, so every test here drives it through the thinnest public wrapper
 * that reaches the branch under study — `getMe` for a plain GET, `updateMe` for a JSON body,
 * `splitPreview` for FormData, `logoutAllSessions` for a 204, `resendVerificationEmail` for
 * `skipAuth`. Which wrapper is used is an implementation detail of the test; the assertion is
 * always about `request`.
 *
 * jsdom, not node, and that is load-bearing. `serverCookieHeader` branches on
 * `typeof window !== 'undefined'`: with a window it returns `{}` immediately, which is the
 * browser path this file describes. Without one it dynamically imports `next/headers` and
 * relies on falling into a `catch` — a different code path, reached by accident, that would
 * make every assertion below a statement about Next internals.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * `@/lib/auth` is mocked rather than exercised, for two reasons. It is the seam the
 * interceptor is defined in terms of — "one refresh, one retry" is a claim about how many
 * times `sharedRefresh` is called, and only a mock can count that. And `handleSessionExpired`
 * assigns `window.location.href`, which jsdom answers with a "Not implemented: navigation"
 * error that would bury the real result. `@/lib/auth` has its own behaviour (single-flight,
 * the proactive timer) and deserves its own suite; this one is about `request`.
 */
const sharedRefresh = vi.fn<() => Promise<{ ok: boolean }>>();
const handleSessionExpired = vi.fn<() => Promise<void>>();
vi.mock('@/lib/auth', () => ({
  sharedRefresh: () => sharedRefresh(),
  handleSessionExpired: () => handleSessionExpired(),
}));

import type { ApiRequestError } from '@/lib/api/client';
import { errorCopy } from '@/lib/strings';

/** Pinned in vitest.config.mts; asserted here so a change to the config surfaces as a failure. */
const BASE = 'https://api.test.invalid';

/**
 * The response shape `request` actually consumes: `ok`, `status`, `statusText`, `json()`,
 * `text()`. A hand-built object rather than a real `Response` because a real one cannot
 * express "2xx whose `.text()` returns empty" and "204" as separate, deliberately chosen
 * cases without fighting the constructor.
 */
function respond(
  status: number,
  options: { body?: unknown; text?: string; statusText?: string } = {},
) {
  const text = options.text ?? (options.body === undefined ? '' : JSON.stringify(options.body));
  return {
    ok: status >= 200 && status < 300,
    status,
    statusText: options.statusText ?? '',
    json: async () => {
      if (!text) throw new SyntaxError('Unexpected end of JSON input');
      return JSON.parse(text) as unknown;
    },
    text: async () => text,
  };
}

const fetchMock = vi.fn<(url: string, init: RequestInit) => Promise<unknown>>();

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock);
  fetchMock.mockReset();
  sharedRefresh.mockReset();
  handleSessionExpired.mockReset();
  handleSessionExpired.mockResolvedValue(undefined);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
});

/**
 * Fresh module instance. Required by the tests that vary `NEXT_PUBLIC_*`, because `USE_MOCKS`
 * and `API_BASE_URL` are read once at module scope, and applied to every test so no ordering
 * between them can matter.
 *
 * Consequence worth knowing before writing a test here: `ApiRequestError` from a reset module
 * is a DIFFERENT class object from one imported at the top of this file, so `instanceof`
 * against a statically imported copy is always false. Take the class out of the same object
 * the function under test came from, as the tests below do.
 */
async function loadClient() {
  vi.resetModules();
  return import('@/lib/api/client');
}

function initOf(call: number): RequestInit {
  return fetchMock.mock.calls[call][1];
}

function headersOf(call: number): Record<string, string> {
  return initOf(call).headers as Record<string, string>;
}

describe('request — the call it makes', () => {
  it('targets NEXT_PUBLIC_API_BASE_URL and sends cookies with caching off', async () => {
    fetchMock.mockResolvedValue(respond(200, { body: { userId: 'u-1' } }));
    const { getMe } = await loadClient();

    await getMe();

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock.mock.calls[0][0]).toBe(`${BASE}/auth/me`);
    // `credentials: 'include'` is what actually carries the httpOnly `nora_access` cookie;
    // without it every authenticated call is a 401 and the interceptor thrashes.
    expect(initOf(0).credentials).toBe('include');
    expect(initOf(0).cache).toBe('no-store');
    expect(headersOf(0).Accept).toBe('application/json');
  });

  it('adds Content-Type: application/json when there is a body', async () => {
    fetchMock.mockResolvedValue(respond(200, { body: { userId: 'u-1' } }));
    const { updateMe } = await loadClient();

    await updateMe({ displayName: 'Ana' });

    expect(headersOf(0)['Content-Type']).toBe('application/json');
    expect(initOf(0).method).toBe('PATCH');
  });

  it('does NOT set Content-Type for FormData, so the browser can write the boundary', async () => {
    // Setting it by hand here is the classic multipart bug: the header goes out without the
    // `boundary=` parameter and the server cannot parse a body that is otherwise correct.
    fetchMock.mockResolvedValue(respond(200, { body: { segments: [] } }));
    const { splitPreview } = await loadClient();

    await splitPreview(new File(['linha'], 'transcricao.txt', { type: 'text/plain' }));

    expect(headersOf(0)['Content-Type']).toBeUndefined();
    expect(initOf(0).body).toBeInstanceOf(FormData);
  });

  it('lets a caller-supplied header through', async () => {
    fetchMock.mockResolvedValue(respond(200, { body: {} }));
    const { login } = await loadClient();

    await login('ana@x.com', 'senha-bem-longa');

    // `X-NORA-Client: web` is how the backend decides to return the session only in httpOnly
    // cookies instead of in the response body. Dropping it silently downgrades an XSS defence.
    expect(headersOf(0)['X-NORA-Client']).toBe('web');
  });
});

describe('request — the 401 interceptor', () => {
  it('refreshes once and retries once on 401', async () => {
    fetchMock
      .mockResolvedValueOnce(respond(401, { body: { code: 'TOKEN_INVALID', message: 'expired' } }))
      .mockResolvedValueOnce(respond(200, { body: { userId: 'u-1' } }));
    sharedRefresh.mockResolvedValue({ ok: true });
    const { getMe } = await loadClient();

    await expect(getMe()).resolves.toEqual({ userId: 'u-1' });

    expect(sharedRefresh).toHaveBeenCalledTimes(1);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    // The retry has to be the same request, not a fresh default one.
    expect(fetchMock.mock.calls[1][0]).toBe(`${BASE}/auth/me`);
  });

  it('does not loop when the retried request is also a 401', async () => {
    // `_isRetry` is the whole reason this cannot become an infinite refresh storm against a
    // backend that is already refusing. Two fetches, one refresh, then an honest throw.
    fetchMock.mockResolvedValue(respond(401, { body: { code: 'TOKEN_INVALID', message: 'nope' } }));
    sharedRefresh.mockResolvedValue({ ok: true });
    const { getMe, ApiRequestError } = await loadClient();

    await expect(getMe()).rejects.toBeInstanceOf(ApiRequestError);

    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(sharedRefresh).toHaveBeenCalledTimes(1);
    expect(handleSessionExpired).not.toHaveBeenCalled();
  });

  it('hands over to handleSessionExpired when the refresh fails, and still throws', async () => {
    // The throw matters even though `handleSessionExpired` navigates away in a real browser:
    // on the server there is no navigation, and a promise that never settles would hang an
    // RSC render instead of producing an error the page can handle.
    fetchMock.mockResolvedValue(
      respond(401, { body: { code: 'REFRESH_TOKEN_INVALID', message: 'gone' } }),
    );
    sharedRefresh.mockResolvedValue({ ok: false });
    const { getMe } = await loadClient();

    await expect(getMe()).rejects.toMatchObject({ status: 401 });

    expect(handleSessionExpired).toHaveBeenCalledTimes(1);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('skipAuth turns a 401 into a plain error — no refresh, no redirect', async () => {
    // On a public endpoint a 401 is the answer, not a session problem. Refreshing there would
    // rotate a perfectly good session because somebody mistyped a password.
    fetchMock.mockResolvedValue(
      respond(401, { body: { code: 'INVALID_CREDENTIALS', message: 'bad' } }),
    );
    const { resendVerificationEmail, ApiRequestError } = await loadClient();

    await expect(resendVerificationEmail('ana@x.com')).rejects.toBeInstanceOf(ApiRequestError);

    expect(sharedRefresh).not.toHaveBeenCalled();
    expect(handleSessionExpired).not.toHaveBeenCalled();
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});

describe('request — error mapping', () => {
  it('prefers the pt-BR copy for a known code over the API message', async () => {
    // The API's `message` is a developer-facing string in English and screens render whatever
    // this throws, verbatim. A known code must never reach a user as English.
    fetchMock.mockResolvedValue(
      respond(403, { body: { code: 'FORBIDDEN', message: 'Access denied' } }),
    );
    const { getMe } = await loadClient();

    await expect(getMe()).rejects.toMatchObject({
      status: 403,
      message: errorCopy.FORBIDDEN,
      payload: { code: 'FORBIDDEN', message: 'Access denied' },
    });
  });

  it('falls back to the API message for a code nobody has written copy for', async () => {
    fetchMock.mockResolvedValue(
      respond(409, { body: { code: 'SOMETHING_NEW', message: 'Conflito no recurso' } }),
    );
    const { getMe } = await loadClient();

    await expect(getMe()).rejects.toMatchObject({ message: 'Conflito no recurso' });
  });

  it('falls back to status + statusText when the error body is not JSON', async () => {
    // A 502 from a proxy is HTML, and `resp.json()` throws on it. The catch has to leave a
    // usable message instead of an empty one.
    fetchMock.mockResolvedValue(
      respond(502, { text: '<html>Bad Gateway</html>', statusText: 'Bad Gateway' }),
    );
    const { getMe } = await loadClient();

    await expect(getMe()).rejects.toMatchObject({
      status: 502,
      message: 'Request failed: 502 Bad Gateway',
    });
  });

  it('throws an ApiRequestError carrying the parsed payload for the caller', async () => {
    fetchMock.mockResolvedValue(
      respond(400, {
        body: {
          code: 'VALIDATION_ERROR',
          message: 'invalid',
          details: [{ field: 'displayName', issue: 'blank' }],
        },
      }),
    );
    const { updateMe, ApiRequestError } = await loadClient();

    const error = await updateMe({ displayName: '' }).catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiRequestError);
    expect((error as ApiRequestError).payload?.details).toEqual([
      { field: 'displayName', issue: 'blank' },
    ]);
  });
});

describe('request — empty and non-empty bodies', () => {
  it('resolves undefined on 204 without touching the body', async () => {
    const response = respond(204);
    const textSpy = vi.spyOn(response, 'text');
    fetchMock.mockResolvedValue(response);
    const { logoutAllSessions } = await loadClient();

    await expect(logoutAllSessions()).resolves.toBeUndefined();
    expect(textSpy).not.toHaveBeenCalled();
  });

  it('resolves undefined on a 2xx with an empty body instead of throwing on JSON.parse', async () => {
    // 202 from `/auth/verify-email/resend` is the live example.
    fetchMock.mockResolvedValue(respond(202, { text: '' }));
    const { resendVerificationEmail } = await loadClient();

    await expect(resendVerificationEmail('ana@x.com')).resolves.toBeUndefined();
  });

  it('parses a 2xx body', async () => {
    fetchMock.mockResolvedValue(respond(200, { body: { id: 'w-1', name: 'Fluxo' } }));
    const { getWorkflow } = await loadClient();

    await expect(getWorkflow('w-1')).resolves.toEqual({ id: 'w-1', name: 'Fluxo' });
  });

  it('percent-encodes a path segment taken from user data', async () => {
    fetchMock.mockResolvedValue(respond(200, { body: {} }));
    const { getWorkflow } = await loadClient();

    await getWorkflow('a/b?c');

    expect(fetchMock.mock.calls[0][0]).toBe(`${BASE}/workflows/a%2Fb%3Fc`);
  });
});

describe('fixtures are opt-in', () => {
  // This module used to default to fixtures, so a build that forgot to set the variable served
  // hardcoded meetings in production. The default was inverted; nothing but this test keeps it
  // inverted. The exact-string comparison is the part worth pinning — `'TRUE'` or `'1'` must
  // NOT be enough, because a truthy-ish check is how the old default comes back by accident.
  it('goes to the network when NEXT_PUBLIC_USE_MOCKS is unset', async () => {
    vi.stubEnv('NEXT_PUBLIC_USE_MOCKS', undefined);
    fetchMock.mockResolvedValue(respond(200, { body: { items: [], totalItems: 0 } }));
    const { listMeetings } = await loadClient();

    await listMeetings();

    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it.each([['TRUE'], ['1'], ['yes'], ['false']])(
    'goes to the network when NEXT_PUBLIC_USE_MOCKS is %j',
    async (value) => {
      vi.stubEnv('NEXT_PUBLIC_USE_MOCKS', value);
      fetchMock.mockResolvedValue(respond(200, { body: { items: [], totalItems: 0 } }));
      const { listMeetings } = await loadClient();

      await listMeetings();

      expect(fetchMock).toHaveBeenCalledTimes(1);
    },
  );

  it('serves the fixture, without any fetch, only for the exact string "true"', async () => {
    vi.stubEnv('NEXT_PUBLIC_USE_MOCKS', 'true');
    const { listMeetings } = await loadClient();

    const result = await listMeetings();

    expect(fetchMock).not.toHaveBeenCalled();
    expect(result.items.length).toBeGreaterThan(0);
  });
});
