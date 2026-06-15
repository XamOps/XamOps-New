import React, { createContext, useContext, ReactNode } from 'react';

interface MaintenanceContextValue {
  isMaintenanceMode: boolean;
}

const MaintenanceContext = createContext<MaintenanceContextValue>({ isMaintenanceMode: false });

export const useMaintenanceMode = () => useContext(MaintenanceContext);

export const MaintenanceProvider: React.FC<{ children: ReactNode }> = ({ children }) => {
  const isMaintenanceMode = false;

  // Health check polling disabled — /api/health returns 404 on prod causing false maintenance mode
  // useEffect(() => {
  //   let cancelled = false;

  //   async function poll() {
  //     const [healthy, flagActive] = await Promise.all([checkHealth(), checkMaintenanceFlag()]);
  //     if (!cancelled) setIsMaintenanceMode(!healthy || flagActive);
  //   }

  //   poll();
  //   const id = setInterval(poll, POLL_INTERVAL_MS);
  //   return () => {
  //     cancelled = true;
  //     clearInterval(id);
  //   };
  // }, []);

  return (
    <MaintenanceContext.Provider value={{ isMaintenanceMode }}>
      {children}
    </MaintenanceContext.Provider>
  );
};
