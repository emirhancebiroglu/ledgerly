import { defineConfig, devices } from "@playwright/test";

const PORT = 3100;
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
  webServer: {
    // `output: "standalone"` (next.config.ts) means `next start` doesn't work against the built
    // output — the standalone server is its own entry point, and (per Next's docs) it doesn't
    // copy `public/` or `.next/static` on its own; this mirrors the same copy the Dockerfile
    // does for prod, cross-platform (no `cp -r`).
    command: `node scripts/copy-standalone-assets.mjs && node .next/standalone/server.js`,
    env: { PORT: String(PORT) },
    url: baseURL,
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
});
