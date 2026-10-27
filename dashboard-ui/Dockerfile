# Build context = dashboard-ui/:   docker build -t dashboard-ui dashboard-ui
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
# The build bakes in only the display timezone; the API address is NOT baked in (nginx proxies /api at runtime).
ARG VITE_TIMEZONE=Asia/Kolkata
ENV VITE_TIMEZONE=$VITE_TIMEZONE
RUN npm run build

FROM nginx:1.27-alpine
ENV DASHBOARD_API_URL=http://dashboard-api:8086 \
    NGINX_RESOLVER=127.0.0.11
# Files in /etc/nginx/templates/*.template are run through envsubst into /etc/nginx/conf.d/ at start-up.
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
HEALTHCHECK --interval=15s --timeout=3s --start-period=5s --retries=3 \
  CMD wget -qO- http://127.0.0.1/healthz >/dev/null || exit 1
