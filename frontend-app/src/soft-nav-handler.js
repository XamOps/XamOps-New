/**
 * Handles soft navigation between pages by fetching content and replacing the main container.
 * This effectively turns multi-page transitions into SPA-like transitions for smoother UX.
 * 
 * @param {Event} event - The click event from the anchor tag
 */
export async function handleSoftNavigation(event) {
    // 1. Prevent default browser navigation
    event.preventDefault();

    // Get the target URL from the clicked link or its closest anchor ancestor
    const link = event.target.closest('a');
    if (!link) return;

    const url = link.href;
    console.log('[SoftNav] Initiating soft navigation to:', url);

    try {
        // 2. Show loading state (optional, can be improved)
        const mainContent = document.getElementById('main-content');
        if (mainContent) {
            mainContent.style.opacity = '0.5';
            mainContent.style.pointerEvents = 'none';
        }

        // 3. Fetch the target page content
        const response = await fetch(url);
        if (!response.ok) throw new Error(`HTTP error! status: ${response.status}`);
        const html = await response.text();

        // 4. Parse the response HTML
        const parser = new DOMParser();
        const doc = parser.parseFromString(html, 'text/html');

        // 5. Extract the new main content
        // We assume consistency in ID usage across pages (#main-content)
        const newMainContent = doc.getElementById('main-content');
        if (!newMainContent) {
            throw new Error('Target page does not contain #main-content');
        }

        // 6. Replace the current main content
        if (mainContent) {
            mainContent.replaceWith(newMainContent);

            // Fade in effect
            newMainContent.style.opacity = '0';
            requestAnimationFrame(() => {
                newMainContent.style.transition = 'opacity 0.3s ease';
                newMainContent.style.opacity = '1';
            });
        }

        // 7. Update the URL state
        const title = doc.title;
        if (title) document.title = title;
        window.history.pushState({}, title, url);

        // 8. Handle SCRIPTS
        // We need to execute scripts that are:
        // a) Inside the new #main-content
        // b) Direct children of body (often used for page-specific init scripts at the end)

        const mainScripts = Array.from(newMainContent.querySelectorAll('script'));
        const bodyScripts = Array.from(doc.body.children).filter(el => el.tagName === 'SCRIPT');
        const scriptsToRun = [...mainScripts, ...bodyScripts];

        // Unique scripts to avoid double execution if logic overlaps (simple check)
        const uniqueScripts = new Set();

        // Context for script execution
        // We override addEventListener to catch DOMContentLoaded from inline scripts
        // and execute the specific initialization immediately.
        const originalAddEventListener = document.addEventListener;
        document.addEventListener = function (type, listener, options) {
            if (type === 'DOMContentLoaded') {
                // console.log('[SoftNav] Trapped DOMContentLoaded listener. Queuing execution.');
                // Execute immediately (next tick) as the DOM is technically ready
                setTimeout(() => {
                    try {
                        listener({ type: 'DOMContentLoaded' });
                    } catch (err) {
                        console.error('[SoftNav] Error in trapped DOMContentLoaded:', err);
                    }
                }, 0);
            } else {
                originalAddEventListener.call(document, type, listener, options);
            }
        };

        try {
            for (const script of scriptsToRun) {
                // Skip if script has src and is already loaded in document
                if (script.src) {
                    if (document.querySelector(`script[src="${script.src}"]`)) {
                        // console.log('[SoftNav] Skipping existing external script:', script.src.split('/').pop());
                        continue;
                    }
                }

                // Add to set to prevent duplicate adds in this loop if hierarchy overlaps
                if (uniqueScripts.has(script)) continue;
                uniqueScripts.add(script);

                const newScript = document.createElement('script');
                Array.from(script.attributes).forEach(attr => newScript.setAttribute(attr.name, attr.value));
                if (script.innerHTML) newScript.innerHTML = script.innerHTML;

                document.body.appendChild(newScript);

                // Remove source script if it was in mainContent to avoid duplicates in DOM
                if (script.parentNode === newMainContent) {
                    script.remove();
                }
            }
        } finally {
            // Restore original listener after a short delay to ensure inline scripts have run
            setTimeout(() => {
                document.addEventListener = originalAddEventListener;
            }, 100);
        }

        // 9. Re-initialize Alpine.js on the new content
        if (window.Alpine) {
            // console.log('[SoftNav] Re-initializing Alpine.js');
            window.Alpine.initTree(document.body); // Use body to ensure we catch everything
        } else {
            console.warn('[SoftNav] Alpine.js not found');
        }

    } catch (error) {
        console.error('[SoftNav] Failed, falling back to hard navigation:', error);
        window.location.href = url;
    }
}
