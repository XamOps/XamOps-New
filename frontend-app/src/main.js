// Import the main stylesheet, which includes Tailwind CSS
import './style.css';

// Import and initialize Alpine.js
import Alpine from 'alpinejs';

// Import Chart.js and register all components
import Chart from 'chart.js/auto';

// Make Alpine and Chart available globally
window.Alpine = Alpine;
window.Chart = Chart;

// STEP 3: Global Dependency Handling
// Initialize Alpine.js and handle page navigation
if (!window._alpineInitialized) {
    // First time initialization
    Alpine.start();
    window._alpineInitialized = true;
    console.log('✅ Alpine.js Initialized (First Load)');

    // Dispatch event to signal Alpine is ready
    setTimeout(() => {
        window.dispatchEvent(new CustomEvent('alpine-ready'));
    }, 50);
} else {
    // Page navigation - destroy and reinitialize
    console.log('✅ Alpine.js page navigation detected, reinitializing...');

    // Wait for DOM to be ready
    const reinitialize = () => {
        try {
            // Destroy all existing Alpine components
            document.querySelectorAll('[x-data]').forEach(el => {
                if (el._x_dataStack) {
                    delete el._x_dataStack;
                }
            });

            // Reinitialize Alpine on the new page
            Alpine.initTree(document.body);
            console.log('✅ Alpine.js components reinitialized');

            // Dispatch event to signal Alpine is ready after reinitialization
            setTimeout(() => {
                window.dispatchEvent(new CustomEvent('alpine-ready'));
            }, 150);
        } catch (error) {
            console.error('❌ Alpine reinitialization error:', error);
        }
    };

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', reinitialize);
    } else {
        // Use setTimeout to ensure HTML is fully parsed
        setTimeout(reinitialize, 100);
    }
}