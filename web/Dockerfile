FROM node:22-alpine AS builder
WORKDIR /workspace
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
ARG VITE_API_BASE_URL=
ARG VITE_AGENT_GATEWAY=http
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL} \
    VITE_AGENT_GATEWAY=${VITE_AGENT_GATEWAY}
RUN npm run build

FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /workspace/dist /usr/share/nginx/html
EXPOSE 80
HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=5 \
    CMD ["wget", "-q", "-O", "/dev/null", "http://127.0.0.1/health"]
