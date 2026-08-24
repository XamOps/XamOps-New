import React, { createContext, useContext, useState, useCallback, ReactNode } from 'react';

// Context for state (actions node, etc.)
interface TopbarStateContextType {
    title: string | null;
    subtitle: string | null;
    /**
     * Node rendered immediately after the page title.
     *
     * A node rather than more string slots because the one caller needs an image (the
     * XamSecure data-provider mark). Kept generic so the shared Topbar does not have to
     * branch on which page it is rendering.
     */
    titleAdornment: ReactNode | null;
    actions: ReactNode | null;
    onHelpClick: (() => void) | null;
}

// Context for dispatchers (setters)
interface TopbarDispatchContextType {
    setTitle: (title: string | null) => void;
    setSubtitle: (subtitle: string | null) => void;
    setTitleAdornment: (node: ReactNode | null) => void;
    setActions: (actions: ReactNode | null) => void;
    clearActions: () => void;
    setOnHelpClick: (handler: (() => void) | null) => void;
}

const TopbarStateContext = createContext<TopbarStateContextType | undefined>(undefined);
const TopbarDispatchContext = createContext<TopbarDispatchContextType | undefined>(undefined);

export const TopbarProvider: React.FC<{ children: ReactNode }> = ({ children }) => {
    const [title, setTitleState] = useState<string | null>(null);
    const [subtitle, setSubtitleState] = useState<string | null>(null);
    const [titleAdornment, setTitleAdornmentState] = useState<ReactNode | null>(null);
    const [actions, setActionsState] = useState<ReactNode | null>(null);
    const [onHelpClick, setOnHelpClickState] = useState<(() => void) | null>(null);

    const setTitle = useCallback((newTitle: string | null) => {
        setTitleState(newTitle);
    }, []);

    const setSubtitle = useCallback((newSubtitle: string | null) => {
        setSubtitleState(newSubtitle);
    }, []);

    // Wrapped in a thunk: React treats a function passed to a setter as an updater, and the
    // adornment can legitimately BE a function component reference.
    const setTitleAdornment = useCallback((node: ReactNode | null) => {
        setTitleAdornmentState(() => node);
    }, []);

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
        setTitle,
        setSubtitle,
        setTitleAdornment,
        setActions,
        clearActions,
        setOnHelpClick
    }), [setTitle, setSubtitle, setTitleAdornment, setActions, clearActions, setOnHelpClick]);

    const stateValue = React.useMemo(() => ({
        title,
        subtitle,
        titleAdornment,
        actions,
        onHelpClick
    }), [title, subtitle, titleAdornment, actions, onHelpClick]);

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
