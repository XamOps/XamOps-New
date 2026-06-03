import React, { createContext, useContext, useState, useEffect, ReactNode } from 'react';

interface MaintenanceContextValue {
  isMaintenanceMode: boolean;
}

const MaintenanceContext = createContext<MaintenanceContextValue>({ isMaintenanceMode: false });

export const useMaintenanceMode = () => useContext(MaintenanceContext);

const POLL_INTERVAL_MS = 30_000;
const HEALTH_TIMEOUT_MS = 5_000;

async function checkHealth(): Promise<boolean> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), HEALTH_TIMEOUT_MS);
  try {
    const res = await fetch('/api/health', { signal: controller.signal });
    return res.ok;
  } catch {
    return false;
  } finally {
    clearTimeout(timer);
  }
}

async function checkMaintenanceFlag(): Promise<boolean> {
  try {
    const res = await fetch('/api/maintenance/status');
    if (!res.ok) return false;
    const data = await res.json();
    return data.active === true;
  } catch {
    return false;
  }
}

export const MaintenanceProvider: React.FC<{ children: ReactNode }> = ({ children }) => {
  const [isMaintenanceMode, setIsMaintenanceMode] = useState(false);

  useEffect(() => {
    let cancelled = false;

    async function poll() {
      const [healthy, flagActive] = await Promise.all([checkHealth(), checkMaintenanceFlag()]);
      if (!cancelled) setIsMaintenanceMode(!healthy || flagActive);
    }

    poll();
    const id = setInterval(poll, POLL_INTERVAL_MS);
    return () => {
      cancelled = true;
      clearInterval(id);
    };
  }, []);

  return (
    <MaintenanceContext.Provider value={{ isMaintenanceMode }}>
      {children}
    </MaintenanceContext.Provider>
  );
};
