import React, { useState } from 'react';
import { useAuth } from '../../contexts/AuthContext';
import clsx from 'clsx';

const UserLogoutCard: React.FC = () => {
    const { userProfile, logout } = useAuth();
    const [isHovered, setIsHovered] = useState(false);

    // Broaden fallback to email or generic "User"
    const displayName = userProfile?.username || userProfile?.email?.split('@')[0] || 'User';

    // DEBUG: Log profile content to understand why username might be missing
    React.useEffect(() => {
        if (userProfile) {
            console.log('👤 UserLogoutCard Debug - Profile Name:', {
                username: userProfile.username,
                email: userProfile.email,
                id: userProfile.id
            });
        }
    }, [userProfile]);

    const handleLogout = async () => {
        await logout();
    };

    return (
        <div
            className="relative w-full h-12 perspective-1000 group cursor-pointer"
            onMouseEnter={() => setIsHovered(true)}
            onMouseLeave={() => setIsHovered(false)}
            role="button"
            tabIndex={0}
            aria-label="User profile and logout toggle"
        >
            <div className={clsx(
                "relative w-full h-full transition-transform duration-500 preserve-3d",
                isHovered && "rotate-x-180"
            )}>
                {/* Front Side: Username */}
                <div className="absolute inset-0 backface-hidden flex items-center px-4 py-2 bg-gray-50 rounded-lg border border-gray-100 shadow-sm">
                    <div className="w-8 h-8 rounded-full bg-gradient-to-br from-indigo-500 to-purple-600 flex items-center justify-center text-white mr-3">
                        <i className="fas fa-user text-sm" />
                    </div>
                    <div className="flex-1 overflow-hidden">
                        <p className="text-sm font-semibold text-gray-700 truncate">{displayName}</p>
                        <p className="text-[10px] text-gray-400 uppercase tracking-wider">Logged In</p>
                    </div>
                </div>

                {/* Back Side: Logout Button */}
                <div className="absolute inset-0 backface-hidden rotate-x-180 flex items-center">
                    <button
                        onClick={handleLogout}
                        className="w-full h-full flex items-center justify-center px-4 py-2 bg-red-50 text-red-600 rounded-lg border border-red-100 hover:bg-red-100 transition-colors font-semibold shadow-inner"
                    >
                        <i className="fas fa-sign-out-alt mr-2" /> Logout
                    </button>
                </div>
            </div>

            {/* CSS for 3D Flip */}
            <style>{`
        .perspective-1000 {
          perspective: 1000px;
        }
        .preserve-3d {
          transform-style: preserve-3d;
        }
        .backface-hidden {
          backface-visibility: hidden;
        }
        .rotate-x-180 {
          transform: rotateX(180deg);
        }
      `}</style>
        </div>
    );
};

export default UserLogoutCard;
