/**
 * RouteChangeHandler
 * 
 * Forces React Router to properly update components on route changes
 * by using a key that changes with the pathname
 */

import React from 'react';
import { useLocation, Outlet } from 'react-router-dom';

const RouteChangeHandler: React.FC = () => {
    const location = useLocation();

    // Log route changes
    React.useEffect(() => {
        console.log('🔄 [RouteChangeHandler] Route changed to:', location.pathname);
    }, [location.pathname]);

    // Force remount by using pathname as key
    return <Outlet key={location.pathname} />;
};

export default RouteChangeHandler;
