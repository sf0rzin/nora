import { NextResponse } from 'next/server';
import type { NextRequest } from 'next/server';

const PROTECTED_PREFIXES = ['/dashboard', '/meetings', '/settings', '/tasks', '/account'];
const AUTH_PREFIXES = ['/auth'];

/**
 * Middleware roda server-side e pode ler cookies httpOnly. Round 2 / 1.3 A
 * trocou `nora_token` (legivel pelo JS, vulneravel a XSS) por `nora_access`
 * (httpOnly). Mesma logica de protecao de rotas; so a fonte do token mudou.
 */
export function middleware(req: NextRequest) {
  const { pathname } = req.nextUrl;
  const token = req.cookies.get('nora_access')?.value;

  // Landing publica: visitantes podem ver, mas usuarios logados
  // sao redirecionados direto pro dashboard (evita fricao de re-entrar
  // pela home pra chegar no produto).
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

  // Usuario logado abrindo /auth/* -> redireciona pro dashboard.
  // Excecoes: paginas com token na URL que precisam abrir mesmo com sessao
  // ativa (ex: convidado pode ter sessao de outro tenant e estar aceitando
  // um convite via link de e-mail).
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
    '/account/:path*',
    '/auth/:path*',
  ],
};
