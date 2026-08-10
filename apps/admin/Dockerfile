# Build context: apps/admin/
# Internal operator console (Next standalone). Behind Easy Auth + IP allowlist on ACA.

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
# HOSTNAME=0.0.0.0 is not a detail: the standalone output's `server.js` binds to
# `process.env.HOSTNAME`, and Docker fills that variable with the container ID. Without
# the line below, Next listens ONLY on the container IP -- the probe gets "connection refused",
# the HEALTHCHECK fails forever and the deploy's `up --wait` never finishes, even though the
# app is up and Caddy (which talks to `admin:3002`) can serve it.
# `web` does not have this problem because it uses `next start`, which assumes 0.0.0.0.
#
# And the probe uses 127.0.0.1, not `localhost`: with HOSTNAME=0.0.0.0 the listener is IPv4-only,
# but the container /etc/hosts also resolves `localhost` to ::1 and BusyBox wget
# tries IPv6 first. The result is identical to the bug above -- connection refused with the
# server up -- and for that very reason easy to misdiagnose twice.
ENV NODE_ENV=production NEXT_TELEMETRY_DISABLED=1 PORT=3002 HOSTNAME=0.0.0.0
RUN addgroup -S nora && adduser -S nora -G nora
COPY --from=build /app/public ./public
COPY --from=build /app/.next/standalone ./
COPY --from=build /app/.next/static ./.next/static
USER nora
EXPOSE 3002
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD wget --quiet --tries=1 --spider http://127.0.0.1:3002/healthz || exit 1
CMD ["node", "server.js"]
