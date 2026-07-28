import { defineConfig, devices } from "@playwright/test";

// Keep e2e isolated from the normal local api (8080) and app servers. Override both ports
// together when a CI runner needs different free ports.
const PORT = Number(process.env.E2E_WEB_PORT ?? 3101);
const API_PORT = Number(process.env.E2E_API_PORT ?? 8081);
const baseURL = `http://localhost:${PORT}`;

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  reporter: process.env.CI ? [["github"], ["html", { open: "never" }]] : "list",
  use: {
    baseURL,
    trace: "on-first-retry",
  },
  projects: [
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"] },
    },
    {
      name: "mobile-chromium",
      use: { ...devices["Pixel 7"] },
    },
  ],
  webServer: [
    {
      // Stands in for apps/api during e2e — real api is a whole Spring Boot + Postgres +
      // Redis stack this harness has no business standing up just to prove the shell renders.
      // Screens that need real API contracts (T4+) will add their own targeted stubs.
      command: "node e2e/mock-api.mjs",
      port: API_PORT,
      env: { MOCK_API_PORT: String(API_PORT) },
      reuseExistingServer: !process.env.CI,
      timeout: 30_000,
    },
    {
      // `output: "standalone"` (next.config.ts) means `next start` doesn't work against the
      // built output — the standalone server is its own entry point, and (per Next's docs) it
      // doesn't copy `public/` or `.next/static` on its own; this mirrors the same copy the
      // Dockerfile does for prod, cross-platform (no `cp -r`).
      command: `node scripts/copy-standalone-assets.mjs && node .next/standalone/server.js`,
      env: { PORT: String(PORT), API_URL: `http://localhost:${API_PORT}` },
      url: baseURL,
      reuseExistingServer: !process.env.CI,
      timeout: 120_000,
    },
  ],
});
