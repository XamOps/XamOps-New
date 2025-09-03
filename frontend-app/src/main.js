// Import the main stylesheet, which includes Tailwind CSS
import './style.css';

// Import and initialize Alpine.js
import Alpine from 'alpinejs';
window.Alpine = Alpine;
Alpine.start();

// Function to load and include the sidebar HTML
async function includeSidebar() {
  const sidebarElement = document.querySelector('[data-include-sidebar]');
  if (sidebarElement) {
    try {
      const response = await fetch('/_sidebar.html');
      if (!response.ok) throw new Error('Sidebar not found');
      const sidebarHtml = await response.text();
      sidebarElement.innerHTML = sidebarHtml;
    } catch (error) {
      console.error('Error loading sidebar:', error);
      sidebarElement.innerHTML = '<p class="text-red-500 p-4">Error loading sidebar.</p>';
    }
  }
}

// Run the function when the page loads
document.addEventListener('DOMContentLoaded', includeSidebar);