# The build stages run on the build machine's own architecture and
# cross-compile: the SPA is architecture-independent and Go cross-compiles
# natively, so multi-arch images (amd64 + arm64) build without slow emulation.
FROM --platform=$BUILDPLATFORM node:22-alpine AS frontend-build
WORKDIR /app/frontend
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM --platform=$BUILDPLATFORM golang:1.26-alpine AS backend-build
ARG TARGETOS
ARG TARGETARCH
WORKDIR /app/backend
COPY backend/go.mod backend/go.sum ./
RUN go mod download
COPY backend/ ./
RUN CGO_ENABLED=0 GOOS=$TARGETOS GOARCH=$TARGETARCH go build -trimpath -o /insitu-ledger ./cmd/server

FROM alpine:3.21
# su-exec: the entrypoint drops root before starting the server.
# The IANA time-zone database is embedded in the binary (INSITU_TIMEZONE),
# so no tzdata package is needed.
RUN apk add --no-cache ca-certificates su-exec
WORKDIR /app
COPY --from=backend-build /insitu-ledger .
COPY --from=frontend-build /app/frontend/build ./static
COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
ENV INSITU_DATA_DIR=/data \
    INSITU_ADDR=:8080
VOLUME /data
EXPOSE 8080
# Assumes the default INSITU_ADDR port.
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD wget -q -O /dev/null http://127.0.0.1:8080/api/health || exit 1
ENTRYPOINT ["docker-entrypoint.sh"]
CMD ["./insitu-ledger"]

LABEL org.opencontainers.image.source="https://github.com/paolostivanin/insitu-ledger" \
      org.opencontainers.image.description="InSitu Ledger - self-hosted personal finance tracker" \
      org.opencontainers.image.licenses="MIT"
