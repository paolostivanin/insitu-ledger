FROM node:22-alpine AS frontend-build
WORKDIR /app/frontend
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM golang:1.24-alpine AS backend-build
WORKDIR /app/backend
COPY backend/go.mod backend/go.sum ./
RUN go mod download
COPY backend/ ./
RUN CGO_ENABLED=0 go build -o /insitu-ledger ./cmd/server

FROM alpine:3.21
RUN apk add --no-cache ca-certificates
WORKDIR /app
COPY --from=backend-build /insitu-ledger .
COPY --from=frontend-build /app/frontend/build ./static
VOLUME /data
EXPOSE 8080
ENV INSITU_DATA_DIR=/data
CMD ["./insitu-ledger"]
