import React, { createContext, useContext, useMemo, useState } from 'react';

/** Shares the hovered timestamp between charts so crosshairs line up. */
interface SyncValue { hoverT: number | null; setHoverT: (t: number | null) => void }
const Ctx = createContext<SyncValue>({ hoverT: null, setHoverT: () => undefined });

export const ChartSyncProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [hoverT, setHoverT] = useState<number | null>(null);
  const value = useMemo(() => ({ hoverT, setHoverT }), [hoverT]);
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
};

export const useChartSync = (): SyncValue => useContext(Ctx);
