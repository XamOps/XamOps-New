# Stage 1: Build the frontend assets
FROM node:20-alpine AS build

# Set working directory
WORKDIR /app

# Survive flaky registry connections instead of failing the whole build
ENV NPM_CONFIG_FETCH_RETRIES=5 \
    NPM_CONFIG_FETCH_RETRY_MINTIMEOUT=20000 \
    NPM_CONFIG_FETCH_RETRY_MAXTIMEOUT=120000 \
    NPM_CONFIG_FETCH_TIMEOUT=600000

# Registry to install from. Defaults to npmjs, so CI is unaffected. Override when
# building from a network that blocks registry.npmjs.org (some corporate LANs drop
# TLS connections by SNI, which looks like ECONNRESET on every tarball):
#   docker build --build-arg NPM_REGISTRY=https://registry.yarnpkg.com .
# Package contents are still verified against the sha512 integrity hashes in
# package-lock.json, so a mirror cannot substitute altered tarballs undetected.
ARG NPM_REGISTRY=https://registry.npmjs.org

# Copy package manifests first for caching
COPY package*.json .npmrc ./

# Install dependencies from the lockfile. The cache mount keeps downloaded
# tarballs between builds, so a network drop resumes instead of restarting.
#
# The post-install check is not redundant. When every tarball fetch fails, npm
# 10.8.2 aborts with "Exit handler never called!" and still exits 0, leaving
# node_modules as ~1000 empty directories. BuildKit caches that layer as a
# success, and the build then dies much later inside `npm run build` with an
# opaque module-resolution error pointing at whichever dependency the build
# touches first. npm writes the hidden lockfile and .bin/ only after packages are
# actually unpacked, so checking for them turns that silent no-op into a failure
# at the step that caused it.
RUN --mount=type=cache,target=/root/.npm \
    npm ci --no-audit --no-fund --registry="$NPM_REGISTRY" \
 && { \
      ok=1; \
      [ -f node_modules/.package-lock.json ] || { echo "  missing node_modules/.package-lock.json (no tarball was unpacked)" >&2; ok=0; }; \
      for b in tsc vite; do \
        [ -x "node_modules/.bin/$b" ] || { echo "  missing node_modules/.bin/$b" >&2; ok=0; }; \
      done; \
      node -e 'import("@iconify/utils").then(m=>{if(typeof m.getIcons!=="function")process.exit(1)})' \
        || { echo "  @iconify/utils did not resolve (needed by scripts/generate-icon-bundle.mjs)" >&2; ok=0; }; \
      if [ "$ok" -ne 1 ]; then \
        echo "ERROR: 'npm ci' reported success but did not install the dependency tree." >&2; \
        echo "       Registry used: $NPM_REGISTRY" >&2; \
        echo "       This is nearly always the registry being unreachable from the build;" >&2; \
        echo "       retry with --build-arg NPM_REGISTRY=<reachable mirror>." >&2; \
        exit 1; \
      fi; \
      echo "npm ci verified: $(find node_modules -name package.json | wc -l) packages installed"; \
    }

# Copy full source
COPY . .

# Build for production (Vite outputs to dist/)
RUN npm run build

# Stage 2: Serve using Nginx for MPA
FROM nginx:1.25-alpine

# Copy MPA nginx config
COPY nginx.conf /etc/nginx/conf.d/default.conf

# Copy built artifacts
COPY --from=build /app/dist /usr/share/nginx/html

# Expose port 80 for HTTP
EXPOSE 80

# Start Nginx
CMD ["nginx", "-g", "daemon off;"]
