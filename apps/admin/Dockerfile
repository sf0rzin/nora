# Build context: apps/admin/
# Console interno do operador (Next standalone). Atrás de Easy Auth + IP allowlist no ACA.

# --- deps ---
FROM node:22-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci

# --- build ---
FROM node:22-alpine AS build
WORKDIR /app
ENV NEXT_TELEMETRY_DISABLED=1
COPY --from=deps /app/node_modules ./node_modules
COPY . .
RUN npm run build

# --- runtime (standalone) ---
FROM node:22-alpine AS runtime
WORKDIR /app
# HOSTNAME=0.0.0.0 nao e detalhe: o `server.js` do output standalone faz bind em
# `process.env.HOSTNAME`, e o Docker preenche essa variavel com o ID do container. Sem
# a linha abaixo o Next escuta SO no IP do container -- `localhost:3002` da "connection
# refused", o HEALTHCHECK falha para sempre e o `up --wait` do deploy nunca conclui,
# ainda que a app esteja no ar e o Caddy (que fala com `admin:3002`) consiga servir.
# O `web` nao tem esse problema porque usa `next start`, que assume 0.0.0.0.
ENV NODE_ENV=production NEXT_TELEMETRY_DISABLED=1 PORT=3002 HOSTNAME=0.0.0.0
RUN addgroup -S nora && adduser -S nora -G nora
COPY --from=build /app/public ./public
COPY --from=build /app/.next/standalone ./
COPY --from=build /app/.next/static ./.next/static
USER nora
EXPOSE 3002
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD wget --quiet --tries=1 --spider http://localhost:3002/healthz || exit 1
CMD ["node", "server.js"]
