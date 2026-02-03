import React from 'react';
import ReactDOM from 'react-dom/client';
// import './lib/highcharts-init'; // Disabled - maps not needed
import App from './App';
import './style.css';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);
