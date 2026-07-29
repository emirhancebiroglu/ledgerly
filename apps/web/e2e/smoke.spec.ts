import { expect, test } from "@playwright/test";

/**
 * Proves the Playwright harness itself is wired correctly (server boots, browser launches,
 * assertions run) — the screens this is meant to protect (responsive breakpoints, motion,
 * `prefers-reduced-motion`) land in their own tasks once there's a shell to test against.
 */
test("unauthenticated visitor is redirected from a protected route to /login", async ({
  page,
}) => {
  await page.goto("/dashboard");
  await expect(page).toHaveURL(/\/login\?next=%2Fdashboard/);
});

test("login page renders the work email and password fields", async ({ page }) => {
  await page.goto("/login");
  await expect(page.getByLabel("Work email")).toBeVisible();
  await expect(page.getByLabel("Password", { exact: true })).toBeVisible();
});
