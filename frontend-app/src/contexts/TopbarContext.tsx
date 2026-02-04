import React, { createContext, useContext, useState, useCallback, ReactNode } from 'react';

// Context for state (actions node, etc.)
interface TopbarStateContextType {
    actions: ReactNode | null;
    onHelpClick: (() => void) | null;
}

// Context for dispatchers (setters)
interface TopbarDispatchContextType {
    setActions: (actions: ReactNode | null) => void;
    clearActions: () => void;
    setOnHelpClick: (handler: (() => void) | null) => void;
}

const TopbarStateContext = createContext<TopbarStateContextType | undefined>(undefined);
const TopbarDispatchContext = createContext<TopbarDispatchContextType | undefined>(undefined);

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

    const dispatchValue = React.useMemo(() => ({
        setActions,
        clearActions,
        setOnHelpClick
    }), [setActions, clearActions, setOnHelpClick]);

    const stateValue = React.useMemo(() => ({
        actions,
        onHelpClick
    }), [actions, onHelpClick]);

    return (
        <TopbarDispatchContext.Provider value={dispatchValue}>
            <TopbarStateContext.Provider value={stateValue}>
                {children}
            </TopbarStateContext.Provider>
        </TopbarDispatchContext.Provider>
    );
};

// Legacy hook for backward compatibility (returns both)
// WARNING: Using this hook will cause re-renders when actions update.
export const useTopbar = () => {
    const state = useContext(TopbarStateContext);
    const dispatch = useContext(TopbarDispatchContext);

    if (state === undefined || dispatch === undefined) {
        throw new Error('useTopbar must be used within a TopbarProvider');
    }

    return { ...state, ...dispatch };
};

// New hook for components that ONLY need to set actions (restricts re-renders)
export const useTopbarActions = () => {
    const dispatch = useContext(TopbarDispatchContext);
    if (dispatch === undefined) {
        throw new Error('useTopbarActions must be used within a TopbarProvider');
    }
    return dispatch;
};
