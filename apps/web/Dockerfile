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
# NEXT_PUBLIC_* sao baked in pelo bundle do Next no build-time. Setar via env var no
# Container App em runtime NAO funciona. Use --build-arg no docker build / build-images.yml
# (ou o default abaixo, que aponta para a URL injetada no Container App envVar).
ARG NEXT_PUBLIC_API_BASE_URL
ARG NEXT_PUBLIC_USE_MOCKS=false
ENV NEXT_PUBLIC_API_BASE_URL=${NEXT_PUBLIC_API_BASE_URL}
ENV NEXT_PUBLIC_USE_MOCKS=${NEXT_PUBLIC_USE_MOCKS}
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
