# ── Build stage ────────────────────────────────────────────────────────────────
# Cross-compiles both architectures, then selects the one matching the image being
# built for as the runnable entrypoint.
#
# The image serves two purposes and both must keep working:
#   1. it runs as the in-cluster agent Deployment, so /xamops-agent has to exist and
#      match the container's architecture
#   2. the Spring Boot static resource server serves the arch-suffixed binaries to
#      host installs (see scripts/install.sh), so both must remain present
#
# Between May 2026 and this change, ENTRYPOINT pointed at /xamops-agent while the
# build only produced /xamops-agent_linux_amd64 and _arm64 — any image built from it
# failed to start with "no such file or directory". Keep the ENTRYPOINT and the copy
# below in agreement.
# Tracks the go directive in go.mod. Raised from 1.21 alongside golang.org/x/crypto
# v0.52.0, which requires 1.25; an older builder image fails outright on the directive.
FROM --platform=$BUILDPLATFORM golang:1.25-alpine AS builder

WORKDIR /app

RUN apk add --no-cache git

COPY go.mod ./
COPY . .
RUN go mod tidy && go mod download

# Stamped into the binary so agent_data_cache.agent_version identifies the image that
# produced the row. Left as "dev" when not passed, which is the correct answer for an
# unversioned local build.
ARG AGENT_VERSION=dev

RUN LDFLAGS="-w -s -X github.com/xamops/agent/internal/version.Version=${AGENT_VERSION}" && \
    CGO_ENABLED=0 GOOS=linux GOARCH=amd64 \
    go build -ldflags="$LDFLAGS" -o /xamops-agent_linux_amd64 ./cmd/agent && \
    CGO_ENABLED=0 GOOS=linux GOARCH=arm64 \
    go build -ldflags="$LDFLAGS" -o /xamops-agent_linux_arm64 ./cmd/agent

# ── Final image ────────────────────────────────────────────────────────────────
FROM gcr.io/distroless/static:nonroot

# Re-declared because ARGs do not cross FROM boundaries. Selecting the entrypoint here
# rather than in the builder is deliberate: the builder is pinned to $BUILDPLATFORM, so
# buildx deduplicates it across target platforms and any arch choice made there leaks the
# build host's architecture into every image. This stage is genuinely per-target.
#
# Do NOT give this ARG a default value. BuildKit populates the predefined platform ARGs
# automatically, but an explicit default shadows that value, so `ARG TARGETARCH=amd64`
# silently pins every platform to amd64 and the arm64 image ships an x86-64 binary.
ARG TARGETARCH

COPY --from=builder /xamops-agent_linux_${TARGETARCH} /xamops-agent
COPY --from=builder /xamops-agent_linux_amd64 /xamops-agent_linux_amd64
COPY --from=builder /xamops-agent_linux_arm64 /xamops-agent_linux_arm64

USER nonroot:nonroot
EXPOSE 8080

ENTRYPOINT ["/xamops-agent"]
