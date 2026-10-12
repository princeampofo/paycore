import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  build: {
    // Built into the Spring app's resources, so the demo ships inside the jar.
    // Only the "demo" profile serves this folder (see application-demo.yml).
    outDir: '../src/main/resources/demo-static',
    emptyOutDir: true,
  },
  server: {
    // `npm run dev` talks to the app running on 8080.
    proxy: { '/v1': 'http://localhost:8080', '/demo': 'http://localhost:8080' },
  },
});
