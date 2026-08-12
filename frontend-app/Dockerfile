# Stage 1: Build the frontend assets
FROM node:20-alpine AS build

# Set working directory
WORKDIR /app

# Survive flaky registry connections instead of failing the whole build
ENV NPM_CONFIG_FETCH_RETRIES=5 \
    NPM_CONFIG_FETCH_RETRY_MINTIMEOUT=20000 \
    NPM_CONFIG_FETCH_RETRY_MAXTIMEOUT=120000 \
    NPM_CONFIG_FETCH_TIMEOUT=600000

# Copy package manifests first for caching
COPY package*.json .npmrc ./

# Install dependencies from the lockfile. The cache mount keeps downloaded
# tarballs between builds, so a network drop resumes instead of restarting.
RUN --mount=type=cache,target=/root/.npm \
    npm ci --no-audit --no-fund

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
