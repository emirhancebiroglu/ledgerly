import { expect, test, type Page } from "@playwright/test";

async function login(page: Page): Promise<void> {
  await page.goto("/login");
  await page.getByLabel("Work email").fill("owner@example.com");
  await page.getByLabel("Password", { exact: true }).fill("password123");
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(page).toHaveURL(/\/dashboard/);
}

test.describe("expense detail", () => {
  test("renders a PDF document through the BFF proxy without a direct download navigation", async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    await login(page);
    // exp-1 -> doc-1, a PDF fixture.
    await page.goto("/expenses/exp-1");

    await expect(page.locator("iframe")).toBeVisible();
    // The page itself never navigated away to the raw content URL.
    await expect(page).toHaveURL(/\/expenses\/exp-1/);
  });

  test("renders an image document", async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    await login(page);
    // exp-2 -> doc-2, a PNG fixture.
    await page.goto("/expenses/exp-2");

    await expect(page.getByRole("img", { name: /cloudbase-receipt.png/ })).toBeVisible();
  });

  test("shows a fallback with a download link for a non-previewable type", async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    await login(page);
    // exp-4 -> doc-4, text/plain.
    await page.goto("/expenses/exp-4");

    await expect(page.getByText(/Preview isn't available/)).toBeVisible();
    await expect(page.getByRole("link", { name: /Download/ })).toBeVisible();
  });

  test("the two ledger entries display and their signed sum is zero", async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    await login(page);
    await page.goto("/expenses/exp-1");

    await expect(page.getByText("Travel Expense")).toBeVisible();
    await expect(page.getByText("Cash / Bank")).toBeVisible();
    await expect(page.getByTestId("ledger-balance")).toHaveText("Balanced");
  });

  test("a NEEDS_REVIEW expense with a null ledgerTransactionId renders without a transaction, not a crash", async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    await login(page);
    // exp-3: NEEDS_REVIEW, ledgerTransactionId is null.
    await page.goto("/expenses/exp-3");

    await expect(page.getByText(/Not posted yet/)).toBeVisible();
    await expect(page.getByText("Needs review", { exact: true }).first()).toBeVisible();
  });

  test("the timeline marks the flagged step distinctly by text, not color alone", async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    await login(page);
    await page.goto("/expenses/exp-3");

    await expect(page.getByText("Needs review", { exact: true }).last()).toBeVisible();
    await expect(page.getByText("Flagged", { exact: true })).toBeVisible();
  });

  test("Back to expenses returns to the list", async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    await login(page);
    await page.goto("/expenses/exp-1");

    await page.getByRole("link", { name: "Back to expenses" }).click();
    await expect(page).toHaveURL(/\/expenses$/);
  });

  test("document above fields, single column, no horizontal scroll below the shell breakpoint", async ({
    page,
  }) => {
    await page.setViewportSize({ width: 375, height: 1400 });
    await login(page);
    await page.goto("/expenses/exp-1");

    const hasHorizontalScroll = await page.evaluate(
      () => document.documentElement.scrollWidth > document.documentElement.clientWidth,
    );
    expect(hasHorizontalScroll).toBe(false);

    const viewerBox = await page.locator("text=Source document").boundingBox();
    const fieldsBox = await page.getByText("Northwind Logistics").first().boundingBox();
    expect(viewerBox).not.toBeNull();
    expect(fieldsBox).not.toBeNull();
    expect(viewerBox!.y).toBeLessThan(fieldsBox!.y);
  });

  test("the document preview scrolls with the page below the shell breakpoint, not sticking over the content beneath it", async ({
    page,
  }) => {
    // A regression check for a real production bug: the preview Card was unconditionally
    // `sticky top-6`, so on a single-column mobile layout it pinned to the viewport top as the
    // page scrolled and visually overlapped whatever was stacked below it — nothing in the
    // above test caught this, since that one only measures document order before any scrolling.
    await page.setViewportSize({ width: 375, height: 1400 });
    await login(page);
    await page.goto("/expenses/exp-1");

    const cardBeforeScroll = await page.locator("text=Source document").boundingBox();
    expect(cardBeforeScroll).not.toBeNull();

    // AppShell scrolls inside its own <main> (html/body are pinned h-dvh overflow-hidden, see
    // globals.css) rather than at the window level — window.scrollY stays 0 no matter how far
    // the page visibly scrolls, so the scroll container itself has to be measured directly.
    const scrollContainer = page.locator("main");
    await scrollContainer.evaluate((el) => el.scrollTo({ top: 5000 }));
    // Sticky positioning re-renders synchronously with scroll; nothing here is async besides
    // Playwright's own event loop turn.
    await page.waitForTimeout(50);
    const actualScrollDistance = await scrollContainer.evaluate((el) => el.scrollTop);
    expect(actualScrollDistance).toBeGreaterThan(50);

    const cardAfterScroll = await page.locator("text=Source document").boundingBox();
    expect(cardAfterScroll).not.toBeNull();
    // A sticky element pinned to the viewport keeps the same y after scrolling regardless of
    // how far the page scrolled; a normally-flowing element moves up by (up to) the same
    // distance the page scrolled. Requiring the card to have moved at least half as far as the
    // page actually scrolled is generous to timing/rounding noise while still failing hard if
    // the card stayed pinned (a sticky regression moves it 0px no matter how far the page goes).
    const cardMovedBy = cardBeforeScroll!.y - cardAfterScroll!.y;
    expect(cardMovedBy).toBeGreaterThan(actualScrollDistance / 2);
  });
});
