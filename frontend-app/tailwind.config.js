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
        // CloudK8s semantic colours. Names only: the values live in
        // src/styles/k8sSemanticTokens.css, so a palette change is that one file.
        'status-good': 'rgb(var(--k8s-status-good) / <alpha-value>)',
        'status-warn': 'rgb(var(--k8s-status-warn) / <alpha-value>)',
        'status-bad': 'rgb(var(--k8s-status-bad) / <alpha-value>)',
        'status-info': 'rgb(var(--k8s-status-info) / <alpha-value>)',
        'status-off': 'rgb(var(--k8s-status-off) / <alpha-value>)',
        'money-saved': 'rgb(var(--k8s-money-saved) / <alpha-value>)',
        'money-extra': 'rgb(var(--k8s-money-extra) / <alpha-value>)',
        'series-capacity': 'rgb(var(--k8s-series-capacity) / <alpha-value>)',
        'series-requested': 'rgb(var(--k8s-series-requested) / <alpha-value>)',
        'series-used': 'rgb(var(--k8s-series-used) / <alpha-value>)',
        'series-suggested': 'rgb(var(--k8s-series-suggested) / <alpha-value>)',
        'series-limit': 'rgb(var(--k8s-series-limit) / <alpha-value>)',
        'series-allocatable': 'rgb(var(--k8s-series-allocatable) / <alpha-value>)',
        'series-overhead': 'rgb(var(--k8s-series-overhead) / <alpha-value>)',
        'feature-workload': 'rgb(var(--k8s-feature-workload) / <alpha-value>)',
        'feature-node': 'rgb(var(--k8s-feature-node) / <alpha-value>)',
        'feature-hpa': 'rgb(var(--k8s-feature-hpa) / <alpha-value>)',
        'feature-vpa': 'rgb(var(--k8s-feature-vpa) / <alpha-value>)',
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