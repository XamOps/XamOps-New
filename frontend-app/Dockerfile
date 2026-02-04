# Stage 1: Build the frontend assets
FROM node:20-alpine AS build

# Set working directory
WORKDIR /app

# Copy package manifests first for caching
COPY package*.json ./

# Install dependencies
RUN npm install --legacy-peer-deps

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
