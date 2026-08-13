# Build context: apps/web/
# Build: docker build -t nora-web apps/web

# --- deps stage ---
FROM node:22-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci

# --- build stage ---
FROM node:22-alpine AS build
WORKDIR /app
ENV NEXT_TELEMETRY_DISABLED=1
COPY --from=deps /app/node_modules ./node_modules
COPY . .
# NEXT_PUBLIC_* are baked into the Next bundle at build time. Setting them as an env var on
# the Container App at runtime does NOT work. Use --build-arg in docker build / build-images.yml
# (or the default below, which points at the URL injected as a Container App envVar).
ARG NEXT_PUBLIC_API_BASE_URL
ARG NEXT_PUBLIC_USE_MOCKS=false
ENV NEXT_PUBLIC_API_BASE_URL=${NEXT_PUBLIC_API_BASE_URL}
ENV NEXT_PUBLIC_USE_MOCKS=${NEXT_PUBLIC_USE_MOCKS}
# The CSP measurement switch (see next.config.mjs). Declared here because Docker DISCARDS a
# --build-arg for an ARG that is not declared, silently: without these two lines,
# `docker build --build-arg NORA_CSP_PROBE=strict` produces an ordinary image and says
# nothing, which is precisely the silent no-op that switch exists to avoid.
ARG NORA_CSP_PROBE=
ENV NORA_CSP_PROBE=${NORA_CSP_PROBE}
RUN npm run build

# --- runtime ---
FROM node:22-alpine AS runtime
WORKDIR /app
ENV NODE_ENV=production NEXT_TELEMETRY_DISABLED=1
RUN addgroup -S nora && adduser -S nora -G nora
COPY --from=build /app/.next ./.next
COPY --from=build /app/public ./public
COPY --from=build /app/package.json ./package.json
COPY --from=build /app/node_modules ./node_modules
USER nora
EXPOSE 3000

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD wget --quiet --tries=1 --spider http://localhost:3000 || exit 1

CMD ["npm", "run", "start"]
