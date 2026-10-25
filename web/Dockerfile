# The web app: static files served by nginx. Build it from this directory, e.g.
# docker build -t pharmacy-web --build-arg API_URL=https://api.example.com .
FROM node:22-alpine AS build
WORKDIR /web
COPY package.json package-lock.json .npmrc ./
RUN npm ci --no-audit --no-fund
COPY . .
# The backend's URL is built into the app; the backend must list this app's origin in WEB_ORIGINS.
ARG API_URL=http://localhost:8080
RUN VITE_API_URL="$API_URL" npm run build

FROM nginx:1.29-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /web/build /usr/share/nginx/html
EXPOSE 80
