// Import the main stylesheet, which includes Tailwind CSS
import './style.css';

// Import and initialize Alpine.js
import Alpine from 'alpinejs';

// Import Chart.js and register all components
import Chart from 'chart.js/auto';

// Make Alpine and Chart available globally
window.Alpine = Alpine;
window.Chart = Chart;

// Initialize Alpine.js
Alpine.start();

console.log('✅ Alpine.js Initialized');