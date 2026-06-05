import React from 'react';

const MaintenancePage: React.FC = () => {
  return (
    <div style={{
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      height: '100vh',
      width: '100vw',
      background: '#0d0d0d',
      fontFamily: "'Inter', sans-serif",
    }}>
      <div style={{
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        gap: '20px',
        textAlign: 'center',
        padding: '48px 40px',
        background: 'rgba(255,255,255,0.03)',
        border: '1px solid rgba(255,255,255,0.08)',
        borderRadius: '20px',
        maxWidth: '480px',
        width: '100%',
        margin: '0 24px',
      }}>
        <div style={{
          width: '60px',
          height: '60px',
          borderRadius: '14px',
          background: 'rgba(255,255,255,0.06)',
          border: '1px solid rgba(255,255,255,0.1)',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          marginBottom: '4px',
        }}>
          <i className="fas fa-tools" style={{ fontSize: '24px', color: 'rgba(255,255,255,0.6)' }}></i>
        </div>

        <div>
          <h1 style={{
            fontSize: '1.5rem',
            fontWeight: 700,
            color: '#ffffff',
            letterSpacing: '-0.3px',
            margin: 0,
          }}>
            Under Maintenance
          </h1>
          <p style={{
            fontSize: '0.9rem',
            color: 'rgba(255,255,255,0.4)',
            marginTop: '6px',
            fontWeight: 500,
            textTransform: 'uppercase',
            letterSpacing: '0.08em',
          }}>
            XamOps
          </p>
        </div>

        <p style={{
          fontSize: '0.9375rem',
          color: 'rgba(255,255,255,0.45)',
          maxWidth: '340px',
          lineHeight: 1.65,
          margin: 0,
        }}>
          We're making improvements to serve you better. We'll be back shortly.
        </p>

        <div style={{
          display: 'flex',
          alignItems: 'center',
          gap: '8px',
          marginTop: '4px',
          padding: '8px 18px',
          borderRadius: '999px',
          background: 'rgba(255,255,255,0.04)',
          border: '1px solid rgba(255,255,255,0.08)',
        }}>
          <span style={{
            width: '7px',
            height: '7px',
            borderRadius: '50%',
            background: 'rgba(255,255,255,0.5)',
            animation: 'maint-pulse 2s ease-in-out infinite',
          }}></span>
          <span style={{ fontSize: '0.8125rem', color: 'rgba(255,255,255,0.4)' }}>
            Checking status…
          </span>
        </div>
      </div>

      <style>{`
        @keyframes maint-pulse {
          0%, 100% { opacity: 1; }
          50% { opacity: 0.3; }
        }
      `}</style>
    </div>
  );
};

export default MaintenancePage;
