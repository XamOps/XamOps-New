import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    // Give jsdom a concrete origin so `window.location` is http://localhost, not about:blank.
    // The app's api client (src/utils/apiClient.ts) reads `window.location.origin` to decide
    // whether a request is "internal", so a real origin makes that branch behave as it does in
    // the browser. (Node's global `fetch` does not resolve relative URLs against jsdom's location,
    // so this is NOT what stops the "Failed to parse URL" rejections — the benign global `fetch`
    // baseline in src/test/setup.ts does that. This just gives tests a realistic origin.)
    environmentOptions: {
      jsdom: {
        url: 'http://localhost',
      },
    },
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
    coverage: {
      reporter: ['text', 'lcov'],
      exclude: ['node_modules/', 'src/test/'],
    },
  },
});
