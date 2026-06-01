# ── Build stage ────────────────────────────────────────────────────────────────
FROM golang:1.21-alpine AS builder

WORKDIR /app

RUN apk add --no-cache git

COPY go.mod ./
COPY . .
RUN go mod tidy && go mod download

RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 \
    go build -ldflags="-w -s" -o /xamops-agent_linux_amd64 ./cmd/agent && \
    CGO_ENABLED=0 GOOS=linux GOARCH=arm64 \
    go build -ldflags="-w -s" -o /xamops-agent_linux_arm64 ./cmd/agent

# ── Final image ────────────────────────────────────────────────────────────────
FROM gcr.io/distroless/static:nonroot

COPY --from=builder /xamops-agent_linux_amd64 /xamops-agent_linux_amd64
COPY --from=builder /xamops-agent_linux_arm64 /xamops-agent_linux_arm64

USER nonroot:nonroot
EXPOSE 8080

ENTRYPOINT ["/xamops-agent"]
