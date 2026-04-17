# ── Build stage ────────────────────────────────────────────────────────────────
FROM golang:1.21-alpine AS builder

WORKDIR /app

# Install git (needed by go mod for some dependencies)
RUN apk add --no-cache git

# Copy go.mod first (go.sum generated below via tidy)
COPY go.mod ./

# Copy all source so go mod tidy can resolve everything, then download
COPY . .
RUN go mod tidy && go mod download

# Build static binary (no CGO, works in distroless)
RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 \
    go build -ldflags="-w -s" -o /xamops-automation ./cmd/autoscaler

# ── Final image (distroless — no shell, minimal attack surface) ────────────────
FROM gcr.io/distroless/static:nonroot

COPY --from=builder /xamops-automation /xamops-automation

USER nonroot:nonroot

# Health/ready server — matches /healthz and /readyz probes in the manifest
EXPOSE 8081

ENTRYPOINT ["/xamops-automation"]
