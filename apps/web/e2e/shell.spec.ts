import { expect, test, type Page } from "@playwright/test";

async function login(page: Page): Promise<void> {
  await page.goto("/login");
  await page.getByLabel("Email").fill("owner@example.com");
  await page.getByLabel("Password").fill("password123");
  await page.getByRole("button", { name: "Log in" }).click();
  await expect(page).toHaveURL(/\/dashboard/);
}

test.describe("app shell", () => {
  test("desktop: sidebar is visible, no hamburger, no horizontal scroll at 1280px", async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    await login(page);

    await expect(page.getByRole("navigation", { name: "Main" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Open navigation" })).toBeHidden();

    const hasHorizontalScroll = await page.evaluate(
      () => document.documentElement.scrollWidth > document.documentElement.clientWidth,
    );
    expect(hasHorizontalScroll).toBe(false);
  });

  test("mobile: sidebar is hidden, drawer opens via hamburger, no horizontal scroll at 360px", async ({
    page,
  }) => {
    await page.setViewportSize({ width: 360, height: 800 });
    await login(page);

    await expect(page.getByRole("navigation", { name: "Main" })).toBeHidden();

    const hasHorizontalScroll = await page.evaluate(
      () => document.documentElement.scrollWidth > document.documentElement.clientWidth,
    );
    expect(hasHorizontalScroll).toBe(false);

    const hamburger = page.getByRole("button", { name: "Open navigation" });
    await expect(hamburger).toBeVisible();
    await hamburger.click();

    const drawerNav = page.getByRole("navigation", { name: "Main" });
    await expect(drawerNav).toBeVisible();

    // Clicking a nav item inside the drawer navigates and closes it.
    await page.getByRole("link", { name: "Expenses" }).click();
    await expect(page).toHaveURL(/\/expenses/);
    await expect(drawerNav).toBeHidden();
  });

  test("no horizontal scroll at the tablet width (768px)", async ({ page }) => {
    await page.setViewportSize({ width: 768, height: 1024 });
    await login(page);

    const hasHorizontalScroll = await page.evaluate(
      () => document.documentElement.scrollWidth > document.documentElement.clientWidth,
    );
    expect(hasHorizontalScroll).toBe(false);
  });

  test("Ctrl+K opens the command palette, Escape closes it, focus returns", async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    await login(page);

    const trigger = page.getByRole("button", { name: "Search or jump to..." });
    await trigger.focus();

    await page.keyboard.press("Control+k");
    const palette = page.getByRole("dialog");
    await expect(palette).toBeVisible();

    await page.keyboard.press("Escape");
    await expect(palette).toBeHidden();
    await expect(trigger).toBeFocused();
  });

  test("Cmd+K (Meta+K) also opens the command palette", async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    await login(page);

    await page.getByRole("button", { name: "Search or jump to..." }).focus();
    await page.keyboard.press("Meta+k");
    await expect(page.getByRole("dialog")).toBeVisible();
  });

  test("arrow keys move the active row in the palette and Enter navigates to it", async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    await login(page);

    await page.getByRole("button", { name: "Search or jump to..." }).click();
    const firstOption = page.getByRole("option", { name: "Go to Dashboard" });
    const secondOption = page.getByRole("option", { name: "Upload a document" });
    await expect(firstOption).toHaveAttribute("aria-selected", "true");

    await page.keyboard.press("ArrowDown");
    await expect(secondOption).toHaveAttribute("aria-selected", "true");
    await expect(firstOption).toHaveAttribute("aria-selected", "false");

    await page.keyboard.press("Enter");
    await expect(page).toHaveURL(/\/upload/);
  });

  test("a click on a quick-jump row navigates and closes the palette", async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    await login(page);

    await page.getByRole("button", { name: "Search or jump to..." }).click();
    await page.getByRole("option", { name: "Go to Review queue" }).click();

    await expect(page).toHaveURL(/\/review/);
    await expect(page.getByRole("dialog")).toBeHidden();
  });

  test("Budgets is a live nav item while Alerts and Policies remain disabled", async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    await login(page);

    await expect(page.getByRole("link", { name: "Budgets" })).toBeVisible();

    for (const label of ["Alerts", "Policies"]) {
      const item = page.getByText(label, { exact: true });
      await expect(item).toBeVisible();
      const isInsideAnchor = await item.evaluate((el) => el.closest("a") !== null);
      expect(isInsideAnchor).toBe(false);
      const disabledAncestor = item.locator("xpath=ancestor-or-self::*[@aria-disabled='true']");
      await expect(disabledAncestor).toHaveCount(1);
    }
  });

  test("mobile drawer traps focus while open and restores it to the hamburger on close", async ({
    page,
  }) => {
    await page.setViewportSize({ width: 360, height: 800 });
    await login(page);

    const hamburger = page.getByRole("button", { name: "Open navigation" });
    await hamburger.click();
    const drawer = page.getByRole("dialog");
    await expect(drawer).toBeVisible();
    // Base UI's focus trap uses hidden focus-guard sentinels that redirect focus back inside via
    // a JS handler fired on the guard's own focus event — reading document.activeElement in the
    // same tick as the Tab keypress can observe focus mid-transit on the guard itself. A short
    // wait per Tab lets that redirect complete before asserting.
    await page.waitForTimeout(300);

    // Tab far past the number of focusable elements inside the drawer (logo, 4 nav links, the
    // disabled org row is not focusable) — if the trap holds, focus never leaves the dialog.
    for (let i = 0; i < 10; i++) {
      await page.keyboard.press("Tab");
      await page.waitForTimeout(50);
      const activeElementInsideDialog = await page.evaluate(() => {
        const active = document.activeElement;
        const dialog = document.querySelector('[role="dialog"]');
        return dialog ? dialog.contains(active) : false;
      });
      expect(activeElementInsideDialog).toBe(true);
    }

    await page.keyboard.press("Escape");
    await expect(drawer).toBeHidden();
    await expect(hamburger).toBeFocused();
  });

  test("review queue nav item shows the count from the dashboard summary", async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    await login(page);

    await expect(page.getByRole("navigation", { name: "Main" }).getByText("3")).toBeVisible();
  });
});
