# --- FINAL FRONTEND DOCKERFILE USING NGINX ---

# --- Stage 1: The Builder (Builds the static files) ---
FROM node:20-alpine AS builder
WORKDIR /app
COPY frontend-app/package*.json ./
RUN npm ci
COPY frontend-app/ .
RUN npm run build

# --- Stage 2: The Final Image (Serves files with Nginx) ---
FROM nginx:stable-alpine
# Copy the built static files from the 'builder' stage into the Nginx web root
COPY --from=builder /app/dist /usr/share/nginx/html
# Copy our custom Nginx configuration for the frontend
COPY frontend-app/nginx.conf /etc/nginx/conf.d/default.conf
# Tell Docker that the container listens on port 80
EXPOSE 80
# The command to start the Nginx server
CMD ["nginx", "-g", "daemon off;"]
