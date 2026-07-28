import { expect, test, type Page } from "@playwright/test";

async function login(page: Page): Promise<void> {
  await page.goto("/login");
  await page.getByLabel("Email").fill("owner@example.com");
  await page.getByLabel("Password").fill("password123");
  await page.getByRole("button", { name: "Log in" }).click();
  await expect(page).toHaveURL(/\/dashboard/);
}

async function resetExpense(page: Page, id: string): Promise<void> {
  // Test-only endpoint (mock-api.mjs) that restores one row to its seeded NEEDS_REVIEW state.
  // Playwright runs this same spec file once per project (chromium, mobile-chromium) against
  // the SAME mock server process — without a reset, whichever project's worker runs first
  // resolves the fixture and the second project's identical test finds it already gone. Called
  // directly against the mock server's port rather than through the BFF proxy: this is
  // test-only wiring, not part of the app's real request path.
  const response = await page.request.post(`http://localhost:8080/api/v1/test/reset-expense/${id}`);
  if (!response.ok()) {
    throw new Error(`Failed to reset fixture ${id}: ${response.status()}`);
  }
}

// Each mutation test targets its own dedicated NEEDS_REVIEW fixture (exp-8..exp-11), reset
// before every run — exp-3 ("Office Depot") is deliberately never touched here, since T4/T5/T6's
// dashboard/expenses-list/expense-detail tests depend on it staying NEEDS_REVIEW for the whole
// `npm run e2e` run.
test.describe("review queue", () => {
  test("checking a row reveals Approve selected; approving removes exactly that row", async ({
    page,
  }) => {
    await resetExpense(page, "exp-8");
    await login(page);
    await page.goto("/review");

    await expect(page.getByRole("button", { name: "Approve selected" })).toBeHidden();

    await page.getByRole("checkbox", { name: /Select Bulk Approve Target/ }).check();
    await expect(page.getByRole("button", { name: "Approve selected" })).toBeVisible();

    await page.getByRole("button", { name: "Approve selected" }).click();

    await expect(page.getByText("Bulk Approve Target", { exact: true })).toBeHidden();
    // A row that was never checked is untouched.
    await expect(page.getByText("Keyboard Target", { exact: true })).toBeVisible();
  });

  test("clicking Approve on a single row removes it without touching the rest", async ({
    page,
  }) => {
    await resetExpense(page, "exp-9");
    await login(page);
    await page.goto("/review");

    const vendorCell = page.getByText("Single Approve Target", { exact: true });
    const row = vendorCell.locator("..");
    await row.getByRole("button", { name: "Approve" }).click();

    await expect(vendorCell).toBeHidden();
    await expect(page.getByText("Keyboard Target", { exact: true })).toBeVisible();
  });

  test("a 409 (already resolved elsewhere) is reported, not silently swallowed", async ({
    page,
  }) => {
    await login(page);
    await page.goto("/review");

    // exp-7 (vendor "Already Resolved Vendor") is stubbed to always 409 on approve — no reset
    // needed, its status never actually changes.
    const vendorCell = page.getByText("Already Resolved Vendor", { exact: true });
    const row = vendorCell.locator("..");
    await row.getByRole("button", { name: "Approve" }).click();

    await expect(page.getByRole("alert").filter({ hasText: "already been resolved" })).toBeVisible();
    // Rolled back, not left removed.
    await expect(vendorCell).toBeVisible();
  });

  test("the flow is keyboard-operable, checkboxes included", async ({ page }) => {
    await resetExpense(page, "exp-10");
    await login(page);
    await page.goto("/review");

    const checkbox = page.getByRole("checkbox", { name: /Select Keyboard Target/ });
    await checkbox.focus();
    await page.keyboard.press("Space");

    await expect(checkbox).toBeChecked();
    await expect(page.getByRole("button", { name: "Approve selected" })).toBeVisible();
  });

  test("correcting a row via the category select resolves it", async ({ page }) => {
    await resetExpense(page, "exp-11");
    await login(page);
    await page.goto("/review");

    await page
      .getByLabel(/Correct category for Correct Target/)
      .selectOption({ label: "Travel" });

    await expect(page.getByText("Correct Target", { exact: true })).toBeHidden();
  });
});

test.describe("reduced motion", () => {
  // `test.use({ reducedMotion: "reduce" })` does not reliably propagate `prefers-reduced-motion`
  // into the page in this environment (confirmed empirically: `matchMedia` reported `false` with
  // it set, `true` with an explicit `page.emulateMedia()` call) — calling it directly per test,
  // before any navigation, is what actually emulates the media feature.

  test("card entry animation and transitions collapse to near-zero duration", async ({
    page,
  }) => {
    await page.emulateMedia({ reducedMotion: "reduce" });
    await login(page);

    const durations = await page.evaluate(() => {
      const el = document.querySelector('[class*="animate-in"]');
      if (!el) return null;
      const style = getComputedStyle(el);
      return { animation: style.animationDuration };
    });

    expect(durations).not.toBeNull();
    // The same element uses a 300ms entry animation without prefers-reduced-motion — under it,
    // globals.css's kill-switch forces animation-duration down to 0.01ms, which the browser's
    // computed style reports in scientific notation ("1e-05s").
    expect(durations!.animation).not.toBe("0.3s");
    expect(durations!.animation).toBe("1e-05s");
  });

  test("command palette open/close still works correctly, just without motion", async ({
    page,
  }) => {
    await page.emulateMedia({ reducedMotion: "reduce" });
    await page.setViewportSize({ width: 1280, height: 900 });
    await login(page);

    await page.keyboard.press("Control+k");
    const palette = page.getByRole("dialog");
    await expect(palette).toBeVisible();

    // The dialog's open/closed STATE is still correct — motion is reduced, not the
    // functionality or the state signal itself.
    await page.keyboard.press("Escape");
    await expect(palette).toBeHidden();
  });
});
