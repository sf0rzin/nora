import { NextResponse } from 'next/server';
import type { NextRequest } from 'next/server';

/**
 * Every route group under `src/app/(app)` must appear here AND in `config.matcher` below.
 *
 * `/usage` was missing from both. The page shipped with US33 and the two lists were never
 * updated, so a signed-out visitor got a 200 on `/usage` while every sibling route redirected
 * to the login page. `middleware-covers-app-routes.test.ts` now derives the expected set from
 * the filesystem, because a hand-maintained copy of a directory listing is exactly what failed
 * here — including in the e2e spec that was written to catch this kind of drift.
 */
export const PROTECTED_PREFIXES = [
  '/dashboard',
  '/meetings',
  '/settings',
  '/tasks',
  '/trends',
  '/usage',
  '/chat',
  '/projects',
  '/integrations',
  '/flows',
];
const AUTH_PREFIXES = ['/auth'];

/**
 * The middleware runs server-side and can read httpOnly cookies. Round 2 / 1.3 A
 * swapped `nora_token` (JS-readable, vulnerable to XSS) for `nora_access`
 * (httpOnly). Same route protection logic; only the token source changed.
 */
export function middleware(req: NextRequest) {
  const { pathname } = req.nextUrl;
  const token = req.cookies.get('nora_access')?.value;

  // Public landing: visitors can see it, but logged-in users
  // are redirected straight to the dashboard (avoids the friction of going back
  // in through the home page to reach the product).
  if (pathname === '/' && token) {
    const url = req.nextUrl.clone();
    url.pathname = '/dashboard';
    return NextResponse.redirect(url);
  }

  const isProtected = PROTECTED_PREFIXES.some((p) => pathname.startsWith(p));
  if (isProtected && !token) {
    const url = req.nextUrl.clone();
    url.pathname = '/auth/login';
    url.searchParams.set('next', pathname);
    return NextResponse.redirect(url);
  }

  // Logged-in user opening /auth/* -> redirects to the dashboard.
  // Exceptions: pages with a token in the URL that must open even with an active
  // session (e.g. an invitee may have a session from another tenant and be accepting
  // an invite via an e-mail link).
  const isAuthPage = AUTH_PREFIXES.some((p) => pathname.startsWith(p));
  if (
    isAuthPage &&
    token &&
    pathname !== '/auth/verify-email' &&
    pathname !== '/auth/password/reset/confirm' &&
    !pathname.startsWith('/auth/invites/accept/')
  ) {
    const url = req.nextUrl.clone();
    url.pathname = '/dashboard';
    return NextResponse.redirect(url);
  }

  return NextResponse.next();
}

export const config = {
  matcher: [
    '/',
    '/dashboard/:path*',
    '/meetings/:path*',
    '/settings/:path*',
    '/tasks/:path*',
    '/trends/:path*',
    '/usage/:path*',
    '/chat/:path*',
    '/projects/:path*',
    '/integrations/:path*',
    '/flows/:path*',
    '/auth/:path*',
  ],
};
