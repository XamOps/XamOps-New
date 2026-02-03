import React, { createContext, useContext, useState, useCallback, ReactNode } from 'react';

interface TopbarContextType {
    actions: ReactNode | null;
    setActions: (actions: ReactNode | null) => void;
    clearActions: () => void;
}

const TopbarContext = createContext<TopbarContextType | undefined>(undefined);

export const TopbarProvider: React.FC<{ children: ReactNode }> = ({ children }) => {
    const [actions, setActionsState] = useState<ReactNode | null>(null);

    const setActions = useCallback((newActions: ReactNode | null) => {
        setActionsState(newActions);
    }, []);

    const clearActions = useCallback(() => {
        setActionsState(null);
    }, []);

    return (
        <TopbarContext.Provider value={{ actions, setActions, clearActions }}>
            {children}
        </TopbarContext.Provider>
    );
};

export const useTopbar = () => {
    const context = useContext(TopbarContext);
    if (context === undefined) {
        throw new Error('useTopbar must be used within a TopbarProvider');
    }
    return context;
};
