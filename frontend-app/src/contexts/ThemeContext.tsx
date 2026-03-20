import React, { createContext, useContext, useState, useEffect, useCallback, useMemo } from 'react';

type Theme = 'light' | 'dark';

interface ThemeContextType {
    theme: Theme;
    toggleTheme: () => void;
    setTheme: (theme: Theme) => void;
}

const ThemeContext = createContext<ThemeContextType | undefined>(undefined);

export const ThemeProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
    // Dark mode disabled — always force light theme
    const [theme] = useState<Theme>('light');

    // Clear any previously saved dark preference
    useEffect(() => {
        localStorage.removeItem('xamops_theme');
        document.documentElement.classList.remove('dark');
    }, []);

    const toggleTheme = useCallback(() => { /* dark mode disabled */ }, []);
    const setTheme = useCallback((_newTheme: Theme) => { /* dark mode disabled */ }, []);

    const value = useMemo(() => ({
        theme,
        toggleTheme,
        setTheme
    }), [theme, toggleTheme, setTheme]);

    return (
        <ThemeContext.Provider value={value}>
            {children}
        </ThemeContext.Provider>
    );
};

export const useTheme = () => {
    const context = useContext(ThemeContext);
    if (context === undefined) {
        throw new Error('useTheme must be used within a ThemeProvider');
    }
    return context;
};
