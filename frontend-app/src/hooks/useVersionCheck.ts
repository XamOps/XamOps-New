import { useEffect, useRef, useState } from 'react';

const POLL_INTERVAL_MS = 5 * 60 * 1000; // 5 minutes

export const useVersionCheck = () => {
    const [updateAvailable, setUpdateAvailable] = useState(false);
    const currentVersion = useRef<string | null>(null);

    useEffect(() => {
        const fetchVersion = async () => {
            try {
                const res = await fetch(`/version.json?t=${Date.now()}`);
                if (!res.ok) return;
                const data = await res.json();
                if (!currentVersion.current) {
                    currentVersion.current = data.version;
                } else if (data.version !== currentVersion.current) {
                    setUpdateAvailable(true);
                }
            } catch {
                // silently ignore — network blip or dev environment
            }
        };

        fetchVersion();
        const interval = setInterval(fetchVersion, POLL_INTERVAL_MS);
        return () => clearInterval(interval);
    }, []);

    return { updateAvailable };
};
