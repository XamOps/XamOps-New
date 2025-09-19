# Stage 1: Build the frontend assets
# Use a Node.js image that matches your development environment
FROM node:18-alpine AS build

# Set the working directory
WORKDIR /app

# Copy package.json and package-lock.json (or yarn.lock)
COPY package*.json ./

# Install dependencies
RUN npm install

# Copy the rest of your application source code
COPY . .

# Run the build script defined in your package.json
# This will create a 'dist' directory with the static files for all MPA entry points
RUN npm run build

# Stage 2: Serve the static files with Nginx
# Use a lightweight Nginx image
FROM nginx:1.25-alpine

# Copy the custom Nginx configuration
# Configured for multi-page application (MPA) serving, proxying exact file requests
COPY nginx.conf /etc/nginx/conf.d/default.conf

# Copy the built assets from the 'build' stage
# The 'dist' folder contains all MPA HTML files (e.g., dashboard.html, billing.html)
COPY --from=build /app/dist /usr/share/nginx/html

# Ensure ownership and permissions for Nginx (optional, but good practice)
RUN chown -R nginx:nginx /usr/share/nginx/html && chmod -R 755 /usr/share/nginx/html

# Expose port 80, the default port for Nginx
EXPOSE 80

# The command to start Nginx when the container starts
CMD ["nginx", "-g", "daemon off;"]