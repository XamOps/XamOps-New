/// <reference types="vite/client" />

// Pulls in Vite's ambient declarations so TypeScript accepts side-effect and asset
// imports it resolves at bundle time — `import './style.css'`, `import './icons/bundle'`,
// `*.svg`, `*.png`, and so on — and types `import.meta.env`. Without this the editor's
// language server flags every CSS side-effect import with ts(2882), since this tsconfig
// does not otherwise reference vite/client.
