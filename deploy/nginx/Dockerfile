# Built from the repository root (it needs web/):
#   docker build -f deploy/nginx/Dockerfile .
FROM node:22 AS web
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ ./
RUN npm run build

FROM nginx:1.27-alpine
COPY deploy/nginx/kirim-proxy-headers.conf /etc/nginx/snippets/kirim-proxy-headers.conf
COPY deploy/nginx/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=web /web/dist /usr/share/nginx/html
