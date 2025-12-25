/**
 * Alpine.js Reactivity Fix
 * 
 * This script ensures Alpine.js is fully initialized before data fetching begins.
 * Include this script BEFORE your Alpine components to fix reactivity issues.
 * 
 * Usage in Alpine components:
 * 
 * init() {
 *     // Use waitForAlpine() to ensure reactivity is ready
 *     this.waitForAlpine(() => {
 *         this.loadData();
 *     });
 * }
 */

(function () {
    'use strict';

    // Track if Alpine is fully ready
    let alpineReady = false;
    const readyCallbacks = [];

    // Listen for Alpine initialization
    document.addEventListener('alpine:init', () => {
        console.log('✅ Alpine.js fully initialized');
        alpineReady = true;

        // Execute any pending callbacks
        readyCallbacks.forEach(callback => callback());
        readyCallbacks.length = 0;
    });

    // Fallback: If alpine:init doesn't fire, check for Alpine existence
    const checkAlpineReady = () => {
        if (window.Alpine && !alpineReady) {
            console.log('✅ Alpine.js detected (fallback check)');
            alpineReady = true;
            readyCallbacks.forEach(callback => callback());
            readyCallbacks.length = 0;
        } else if (!alpineReady) {
            setTimeout(checkAlpineReady, 50);
        }
    };

    // Start checking after a short delay
    setTimeout(checkAlpineReady, 100);

    // Add helper method to Alpine's magic properties
    document.addEventListener('alpine:init', () => {
        if (window.Alpine) {
            // Add $waitForAlpine magic helper
            window.Alpine.magic('waitForAlpine', () => {
                return (callback) => {
                    if (alpineReady) {
                        // Alpine is ready, execute immediately on next tick
                        setTimeout(callback, 0);
                    } else {
                        // Wait for Alpine to be ready
                        readyCallbacks.push(callback);
                    }
                };
            });
        }
    });

    // Global helper function for non-Alpine code
    window.waitForAlpine = function (callback) {
        if (alpineReady) {
            setTimeout(callback, 0);
        } else {
            readyCallbacks.push(callback);
        }
    };

    console.log('🔧 Alpine.js reactivity fix loaded');
})();
