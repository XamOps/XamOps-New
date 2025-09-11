# CORRECTED VERSION FOR frontend-app/Dockerfile

# --- Stage 1: The Builder ---
FROM node:20-alpine AS builder

# Set the working directory for the entire build
WORKDIR /app

# Copy package.json and package-lock.json from the frontend-app sub-directory first
COPY frontend-app/package*.json ./

# Install project dependencies
RUN npm install

# Copy the rest of the application source code from the frontend-app sub-directory
COPY frontend-app/ .

# Run the production build script
RUN npm run build


# --- Stage 2: The Final Image ---
FROM node:20-alpine

WORKDIR /app

# Copy the build output, dependencies, and necessary config from the builder stage
COPY --from=builder /app/dist ./dist
COPY --from=builder /app/node_modules ./node_modules
COPY --from=builder /app/package.json ./package.json

# If you have a vite.config.js or similar, it's good practice to copy it too
# COPY --from=builder /app/vite.config.js ./vite.config.js

# Expose the port the application will run on
EXPOSE 3000

# The command to start the Vite preview server
# The '--host' flag is crucial to make it accessible from outside the container
CMD ["npm", "run", "preview", "--", "--port", "3000", "--host"]
