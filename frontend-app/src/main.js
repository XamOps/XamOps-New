// Import the main stylesheet, which includes Tailwind CSS
import './style.css';

// Import and initialize Alpine.js
import Alpine from 'alpinejs';

// Import Chart.js and register all components
import Chart from 'chart.js/auto';

// Make Alpine and Chart available globally
window.Alpine = Alpine;
window.Chart = Chart;

// Don't auto-start Alpine - let pages register their components first
// Pages should call Alpine.start() manually or we'll start it after a delay
// to allow component registration

// Wait a bit for page-specific components to register, then start Alpine
setTimeout(() => {
    if (!Alpine.initialised) {
        Alpine.start();
        console.log('✅ Alpine.js Initialized (auto-start)');
    }
}, 0);

console.log('✅ Alpine.js loaded and ready for component registration');

// --- Soft Navigation Handler ---
import { handleSoftNavigation } from './soft-nav-handler.js';

document.addEventListener('click', (e) => {
    const link = e.target.closest('.soft-nav-link');
    if (link) {
        handleSoftNavigation(e);
    }
});
