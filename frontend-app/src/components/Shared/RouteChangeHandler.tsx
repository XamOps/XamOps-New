/**
 * RouteChangeHandler
 *
 * Forces React Router to properly update components on route changes
 * by using a key that changes with the pathname.
 *
 * Also the single place page-time telemetry is driven from. Every authenticated route in
 * App.tsx is nested inside this element, so one effect here covers the whole product without
 * each page needing to opt in. The tracker owns all the timing and batching; this only tells
 * it which route is now current.
 */

import React from 'react';
import { useLocation, Outlet } from 'react-router-dom';
import { trackNavigation } from '../../utils/pageTimeTracker';

const RouteChangeHandler: React.FC = () => {
    const location = useLocation();

    React.useEffect(() => {
        trackNavigation(location.pathname);
    }, [location.pathname]);

    return <Outlet />;
};

export default RouteChangeHandler;
