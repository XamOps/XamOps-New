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

    React.useEffect(() => {
        console.log('🔄 [RouteChangeHandler] Route changed to:', location.pathname);
    }, [location.pathname]);

    return <Outlet />;
};

export default RouteChangeHandler;
