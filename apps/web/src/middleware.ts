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
  // Excecoes: paginas com token na URL que precisam abrir mesmo com sessao
  // ativa (ex: convidado pode ter sessao de outro tenant e estar aceitando
  // um convite via link de e-mail).
  const isAuthPage = AUTH_PREFIXES.some((p) => pathname.startsWith(p));
  if (
    isAuthPage &&
    token &&
    pathname !== "/auth/verify-email" &&
    pathname !== "/auth/password/reset/confirm" &&
    !pathname.startsWith("/auth/invites/accept/")
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
