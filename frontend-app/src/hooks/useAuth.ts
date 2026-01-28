/**
 * useAuth Hook
 *
 * Provides authentication status by checking cookies
 * Re-checks on location changes to ensure cookies are fresh
 */

import { useState, useEffect } from 'react';
import { useLocation } from 'react-router-dom';

export const useAuth = () => {
  const location = useLocation();
  const [isAuthenticated, setIsAuthenticated] = useState(false);

  useEffect(() => {
    // Check authentication status
    const checkAuth = () => {
      const cookies = document.cookie;
      const hasAuth = cookies.includes('XSRF-TOKEN') ||
                     cookies.includes('JSESSIONID') ||
                     cookies.includes('SESSION');

      setIsAuthenticated(hasAuth);
    };

    // Check immediately
    checkAuth();

    // Also check when location changes
  }, [location]);

  return { isAuthenticated };
};
