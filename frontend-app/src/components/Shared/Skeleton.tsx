import React from 'react';
import clsx from 'clsx';

interface SkeletonProps {
    className?: string;
    variant?: 'text' | 'circular' | 'rectangular' | 'rounded';
    width?: string | number;
    height?: string | number;
}

const Skeleton: React.FC<SkeletonProps> = ({
    className,
    variant = 'text',
    width,
    height,
}) => {
    const baseClasses = 'animate-pulse bg-gray-200/80';

    const variantClasses = {
        text: 'rounded-md',
        circular: 'rounded-full',
        rectangular: 'rounded-none',
        rounded: 'rounded-xl',
    };

    return (
        <div
            className={clsx(baseClasses, variantClasses[variant], className)}
            style={{
                width: width,
                height: height,
            }}
        >
            <span className="sr-only">Loading...</span>
        </div>
    );
};

export default Skeleton;
