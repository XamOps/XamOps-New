// Import the main stylesheet, which includes Tailwind CSS
import './style.css';

// Import and initialize Alpine.js
import Alpine from 'alpinejs';

// STEP 3: Global Dependency Handling
// We guard the initialization to prevent errors during Soft Navigation.
// Even if this script is loaded again, Alpine will only start once.
if (!window.Alpine) {
    window.Alpine = Alpine;
    Alpine.start();

    console.log('✅ Alpine.js Initialized (Single Instance)');
}