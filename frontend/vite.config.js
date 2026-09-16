import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { fileURLToPath } from "node:url";

export default defineConfig({
  plugins: [react()],
  build: {
    rollupOptions: {
      input: {
        main: fileURLToPath(new URL("./index.html", import.meta.url)),
        rankings: fileURLToPath(new URL("./rankings.html", import.meta.url)),
        killCompetitions: fileURLToPath(new URL("./kill-competitions.html", import.meta.url)),
        bingos: fileURLToPath(new URL("./bingos.html", import.meta.url)),
        feedback: fileURLToPath(new URL("./feedback.html", import.meta.url)),
      },
    },
  },
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      "/api": {
        target: "http://localhost:8080",
        changeOrigin: false,
      },
    },
  },
});
