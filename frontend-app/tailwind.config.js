// tailwind.config.js

/** @type {import('tailwindcss').Config} */
export default {
  content: [
    "./*.html",
    "./billops/*.html",
    "./**/*.html",
    "!./node_modules",
    "./src/**/*.{js,jsx,ts,tsx}",
    "./src/**/*.css",
  ],
  darkMode: 'class',
  theme: {
    extend: {
      colors: {
        // Pump-style neutral surface tokens
        'surface-sidebar': 'rgba(247,247,247,0.5)',
        'border-subtle': 'rgba(0,0,0,0.06)',
        'text-default': 'rgba(0,0,0,0.88)',
        'text-subtle': 'rgba(0,0,0,0.6)',
        'surface-hover': 'rgba(0,0,0,0.06)',
        // Pump green brand
        'pump-green': '#1BE68B',
        'pump-green-active': '#32E97A',
      },
      fontFamily: {
        // Slot for PPMonument display numbers — falls back to system sans
        display: ['PPMonument', 'ui-sans-serif', 'system-ui', 'sans-serif'],
      },
      fontSize: {
        'nav': ['13px', { lineHeight: '1.4' }],
      },
      width: {
        'sidebar': '220px',
      },
      height: {
        'nav-item': '33px',
        'topbar': '48px',
      },
      borderColor: {
        subtle: 'rgba(0,0,0,0.06)',
      },
      backgroundColor: {
        'sidebar': 'rgba(247,247,247,0.5)',
        'nav-active': 'rgba(0,0,0,0.06)',
        'nav-hover': 'rgba(0,0,0,0.06)',
      },
    },
  },
  plugins: [],
}