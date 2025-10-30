// tailwind.config.js

/** @type {import('tailwindcss').Config} */
export default {
  content: [
    "./*.html",           // Root HTML files
    "./billops/*.html",   // BillOps
    "./**/*.html",        // All other HTML (but NOT node_modules)
    "!./node_modules",    // Exclude node_modules
    "./src/**/*.js",
    "./src/**/*.css",
  ],
  theme: { extend: {} },
  plugins: [],
}