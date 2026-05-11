import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";

const PROTECTED_PREFIXES = ["/dashboard", "/meetings", "/settings"];
const AUTH_PREFIXES = ["/auth"];

export function middleware(req: NextRequest) {
  const { pathname } = req.nextUrl;
  const token = req.cookies.get("nora_token")?.value;

  const isProtected = PROTECTED_PREFIXES.some((p) => pathname.startsWith(p));
  if (isProtected && !token) {
    const url = req.nextUrl.clone();
    url.pathname = "/auth/login";
    url.searchParams.set("next", pathname);
    return NextResponse.redirect(url);
  }

  // Usuario logado abrindo /auth/* -> redireciona pro dashboard.
  const isAuthPage = AUTH_PREFIXES.some((p) => pathname.startsWith(p));
  if (
    isAuthPage &&
    token &&
    pathname !== "/auth/verify-email" &&
    pathname !== "/auth/password/reset/confirm"
  ) {
    const url = req.nextUrl.clone();
    url.pathname = "/dashboard";
    return NextResponse.redirect(url);
  }

  return NextResponse.next();
}

export const config = {
  matcher: ["/dashboard/:path*", "/meetings/:path*", "/settings/:path*", "/auth/:path*"],
};
