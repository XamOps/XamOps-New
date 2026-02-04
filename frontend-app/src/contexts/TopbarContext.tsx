import React, { createContext, useContext, useState, useCallback, ReactNode } from 'react';

interface TopbarContextType {
    actions: ReactNode | null;
    setActions: (actions: ReactNode | null) => void;
    clearActions: () => void;
    onHelpClick: (() => void) | null;
    setOnHelpClick: (handler: (() => void) | null) => void;
}

const TopbarContext = createContext<TopbarContextType | undefined>(undefined);

export const TopbarProvider: React.FC<{ children: ReactNode }> = ({ children }) => {
    const [actions, setActionsState] = useState<ReactNode | null>(null);
    const [onHelpClick, setOnHelpClickState] = useState<(() => void) | null>(null);

    const setActions = useCallback((newActions: ReactNode | null) => {
        setActionsState(newActions);
    }, []);

    const clearActions = useCallback(() => {
        setActionsState(null);
    }, []);

    const setOnHelpClick = useCallback((handler: (() => void) | null) => {
        setOnHelpClickState(() => handler);
    }, []);

    return (
        <TopbarContext.Provider value={{ actions, setActions, clearActions, onHelpClick, setOnHelpClick }}>
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
