# XamOps CloudShell image
#
# Each CloudShell session (see CloudShellSocketHandler) runs one short-lived container
# from this image, pre-authenticated to the selected cloud account via env vars. The
# image ships the CLIs an operator needs so no AWS/GCP/Azure console login is required:
#
#   - AWS CLI v2 + Session Manager plugin  -> `aws ssm start-session --target <id>` to
#     reach ANY SSM-managed EC2 with no SSH key, no public IP, no open port 22
#   - gcloud (Google Cloud CLI)            -> GCP sessions
#   - az (Azure CLI)                       -> Azure sessions
#   - kubectl + helm                       -> EKS/GKE/AKS access
#   - common tooling: bash, jq, curl, git, less, vim, ssh, unzip
#
# Pin by digest in production: set cloudshell.image to
#   <registry>/xamops-shell@sha256:<digest>
# so a moved tag can never change what runs with a customer's credentials.
#
# Multi-arch: build with `docker buildx ... --platform linux/amd64,linux/arm64`.
# TARGETARCH is provided automatically by buildx per target platform.
FROM ubuntu:22.04

ARG TARGETARCH
ENV DEBIAN_FRONTEND=noninteractive \
    AWS_PAGER="" \
    TERM=xterm-256color \
    LANG=C.UTF-8

# Some build networks MITM plain-HTTP egress and reject the default archive.ubuntu.com
# mirrors with 403. Switch apt to an HTTPS mirror so the build works behind such proxies;
# it is also simply better practice. Verify-Peer is relaxed only for this first update
# because ca-certificates is not installed yet; it is installed on the very next line and
# every later fetch verifies normally.
RUN sed -i 's|http://archive.ubuntu.com|https://mirrors.edge.kernel.org|g; s|http://security.ubuntu.com|https://mirrors.edge.kernel.org|g' /etc/apt/sources.list

# Base tooling. --no-install-recommends keeps the image lean; ca-certificates and gnupg
# are needed to add the gcloud/azure apt repos below.
#
# TLS verification is disabled for THIS bootstrap layer only, via a temporary apt.conf
# fragment that is deleted at the end of the same RUN. Two independent reasons:
#   1. ca-certificates is not installed yet, so apt has no trust store to verify against.
#   2. some build networks terminate TLS at a corporate proxy with a private CA that the
#      base image does not trust.
# ca-certificates is installed in this step, the override is removed, and every
# subsequent fetch (AWS/SSM/kubectl/helm/gcloud/az) verifies normally.
RUN printf 'Acquire::https::Verify-Peer "false";\nAcquire::https::Verify-Host "false";\n' > /etc/apt/apt.conf.d/99insecure-bootstrap \
    && apt-get update && apt-get install -y --no-install-recommends \
        bash \
        ca-certificates \
        curl \
        git \
        gnupg \
        jq \
        less \
        lsb-release \
        openssh-client \
        unzip \
        vim-tiny \
    && rm -f /etc/apt/apt.conf.d/99insecure-bootstrap \
    && rm -rf /var/lib/apt/lists/*

# --- AWS CLI v2 + SSM Session Manager plugin -------------------------------------------
# Both ship per-arch bundles. Map Docker's TARGETARCH (amd64/arm64) to AWS's naming
# (x86_64/aarch64) so the same Dockerfile builds on either platform.
RUN set -eux; \
    case "${TARGETARCH}" in \
        amd64)  AWS_ARCH="x86_64";  SSM_ARCH="64bit"    ;; \
        arm64)  AWS_ARCH="aarch64"; SSM_ARCH="arm64"    ;; \
        *) echo "Unsupported TARGETARCH: ${TARGETARCH}" >&2; exit 1 ;; \
    esac; \
    curl -fsSL "https://awscli.amazonaws.com/awscli-exe-linux-${AWS_ARCH}.zip" -o /tmp/awscliv2.zip; \
    unzip -q /tmp/awscliv2.zip -d /tmp; \
    /tmp/aws/install; \
    curl -fsSL "https://s3.amazonaws.com/session-manager-downloads/plugin/latest/ubuntu_${SSM_ARCH}/session-manager-plugin.deb" -o /tmp/ssm.deb; \
    apt-get update; \
    apt-get install -y /tmp/ssm.deb; \
    rm -rf /tmp/aws /tmp/awscliv2.zip /tmp/ssm.deb /var/lib/apt/lists/*; \
    aws --version; \
    session-manager-plugin --version

# --- kubectl + helm --------------------------------------------------------------------
RUN set -eux; \
    KVER="$(curl -fsSL https://dl.k8s.io/release/stable.txt)"; \
    curl -fsSL "https://dl.k8s.io/release/${KVER}/bin/linux/${TARGETARCH}/kubectl" -o /usr/local/bin/kubectl; \
    chmod +x /usr/local/bin/kubectl; \
    kubectl version --client=true; \
    curl -fsSL https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash; \
    helm version

# --- gcloud (Google Cloud CLI) ---------------------------------------------------------
RUN set -eux; \
    curl -fsSL https://packages.cloud.google.com/apt/doc/apt-key.gpg \
        | gpg --dearmor -o /usr/share/keyrings/cloud.google.gpg; \
    echo "deb [signed-by=/usr/share/keyrings/cloud.google.gpg] https://packages.cloud.google.com/apt cloud-sdk main" \
        > /etc/apt/sources.list.d/google-cloud-sdk.list; \
    apt-get update; \
    apt-get install -y --no-install-recommends google-cloud-cli; \
    rm -rf /var/lib/apt/lists/*; \
    gcloud --version

# --- az (Azure CLI) --------------------------------------------------------------------
RUN set -eux; \
    curl -fsSL https://packages.microsoft.com/keys/microsoft.asc \
        | gpg --dearmor -o /usr/share/keyrings/microsoft.gpg; \
    AZ_DIST="$(lsb_release -cs)"; \
    echo "deb [arch=${TARGETARCH} signed-by=/usr/share/keyrings/microsoft.gpg] https://packages.microsoft.com/repos/azure-cli/ ${AZ_DIST} main" \
        > /etc/apt/sources.list.d/azure-cli.list; \
    apt-get update; \
    apt-get install -y --no-install-recommends azure-cli; \
    rm -rf /var/lib/apt/lists/*; \
    az version

# Non-root by default. The shell only ever runs tools against injected credentials; it
# has no reason to be root inside the container. Handlers may still override the user.
RUN useradd --create-home --shell /bin/bash xamops
USER xamops
WORKDIR /home/xamops

CMD ["/bin/bash", "-i"]
