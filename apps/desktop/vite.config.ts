import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import path from "path";

const host = process.env.TAURI_DEV_HOST;

export default defineConfig(async () => ({
  plugins: [react()],
  resolve: {
    alias: {
      "@": path.resolve(import.meta.dirname, "./src"),
    },
  },
  clearScreen: false,
  build: {
    rollupOptions: {
      // Two entry points, and no `main`: the main window loads the remote web app
      // (`app.windows[0].url` in tauri.conf.json), so the desktop bundle only ships
      // the two windows it actually renders itself.
      input: {
        overlay: path.resolve(import.meta.dirname, "overlay.html"),
        dock: path.resolve(import.meta.dirname, "dock.html"),
      },
    },
  },
  server: {
    port: 1420,
    strictPort: true,
    host: host || false,
    hmr: host
      ? {
          protocol: "ws",
          host,
          port: 1421,
        }
      : undefined,
    watch: {
      ignored: ["**/src-tauri/**"],
    },
  },
}));
