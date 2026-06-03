import React from 'react';
import '../styles/login.css';

const MaintenancePage: React.FC = () => {
  return (
    <div className="login-container">
      {/* Background blur circles — same as LoginPage */}
      <div className="bg-blur-circle circle-1"></div>
      <div className="bg-blur-circle circle-2"></div>
      <div className="grid-overlay"></div>

      <div style={{
        position: 'relative',
        zIndex: 10,
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        width: '100%',
        height: '100%',
        gap: '24px',
        textAlign: 'center',
        padding: '0 24px',
      }}>
        <div style={{
          width: '64px',
          height: '64px',
          borderRadius: '16px',
          background: 'linear-gradient(135deg, #3B82F6, #8B5CF6)',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          marginBottom: '8px',
        }}>
          <i className="fas fa-tools" style={{ fontSize: '28px', color: '#fff' }}></i>
        </div>

        <h1 style={{
          fontSize: '2rem',
          fontWeight: 700,
          color: '#F9FAFB',
          letterSpacing: '-0.5px',
        }}>
          XamOps is Under Maintenance
        </h1>

        <p style={{
          fontSize: '1rem',
          color: '#9CA3AF',
          maxWidth: '420px',
          lineHeight: 1.6,
        }}>
          We'll be back shortly. Thank you for your patience.
        </p>

        <div style={{
          display: 'flex',
          alignItems: 'center',
          gap: '8px',
          marginTop: '8px',
          padding: '10px 20px',
          borderRadius: '999px',
          background: 'rgba(255,255,255,0.05)',
          border: '1px solid rgba(255,255,255,0.08)',
        }}>
          <span style={{
            width: '8px',
            height: '8px',
            borderRadius: '50%',
            background: '#F59E0B',
            animation: 'pulse 2s infinite',
          }}></span>
          <span style={{ fontSize: '0.875rem', color: '#9CA3AF' }}>Checking status...</span>
        </div>
      </div>
    </div>
  );
};

export default MaintenancePage;
