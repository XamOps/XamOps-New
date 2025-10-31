// Import the main stylesheet, which includes Tailwind CSS
import './style.css';

// Import and initialize Alpine.js
import Alpine from 'alpinejs';
window.Alpine = Alpine;
Alpine.start();

document.querySelectorAll('[data-include-sidebar]').forEach(el => {
    fetch('/_sidebar.html')  // or './_sidebar.html' or absolute path
        .then(res => {
            if (!res.ok) throw new Error('Failed to load sidebar');
            return res.text();
        })
        .then(html => {
            el.innerHTML = html;
            // Critical: Init Alpine on the new content
            if (typeof Alpine !== 'undefined' && Alpine.initTree) {
                Alpine.initTree(el);
            }
        })
        .catch(err => console.error('Sidebar include failed:', err));
});