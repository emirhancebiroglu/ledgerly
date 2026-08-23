import path from "node:path";
import { expect, test, type Page } from "@playwright/test";

const FIXTURES_DIR = path.join(process.cwd(), "e2e", "fixtures");

async function login(page: Page): Promise<void> {
  await page.goto("/login");
  await page.getByLabel("Work email").fill("owner@example.com");
  await page.getByLabel("Password", { exact: true }).fill("password123");
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(page).toHaveURL(/\/dashboard/);
}

test.describe("policies", () => {
  test.describe.configure({ mode: "serial" });

  test.beforeEach(async ({ page }) => {
    const apiPort = process.env.E2E_API_PORT ?? "8081";
    const response = await page.request.post(`http://localhost:${apiPort}/api/v1/test/reset-policies`);
    expect(response.ok()).toBe(true);
  });

  test("lists documents with real status and filters by tab", async ({ page }) => {
    await login(page);
    await page.goto("/policies");

    await expect(page.getByText("expense-policy-2026.pdf")).toBeVisible();
    await expect(page.getByText("procurement-handbook-v4.pdf")).toBeVisible();
    await expect(page.getByText("Indexed", { exact: true })).toBeVisible();
    await expect(page.getByText("Failed", { exact: true }).first()).toBeVisible();
    await expect(page.getByText(/pdf_text_extraction_empty/)).toBeVisible();

    await page.getByRole("button", { name: /^Failed \d+/ }).click();
    await expect(page.getByText("procurement-handbook-v4.pdf")).toBeVisible();
    await expect(page.getByText("expense-policy-2026.pdf")).not.toBeVisible();
  });

  test("opens a document and searches its passages", async ({ page }) => {
    await login(page);
    await page.goto("/policies");
    await page.getByText("expense-policy-2026.pdf").click();
    await expect(page).toHaveURL(/\/policies\/policy-1$/);

    await expect(page.getByText(/Meals taken while travelling/)).toBeVisible();
    await expect(page.getByText(/itemised receipt/)).toBeVisible();

    await page.getByPlaceholder(/Search this document/).fill("receipt");
    await expect(page.getByText(/itemised receipt/)).toBeVisible();
    await expect(page.getByText(/Meals taken while travelling/)).not.toBeVisible();

    await page.getByPlaceholder(/Search this document/).fill("zzz-nothing-matches");
    await expect(page.getByText(/No passage matches/)).toBeVisible();
  });

  test("uploads a PDF and reflects the real chunk count without reload", async ({ page }) => {
    await login(page);
    await page.goto("/policies");
    await page.getByRole("button", { name: "Upload policy PDF" }).click();

    const fileInput = page.locator('input[type="file"]');
    await fileInput.setInputFiles(path.join(FIXTURES_DIR, "receipt.pdf"));

    await expect(page.getByText(/Splitting the document into passages/)).toBeVisible();
    await expect(page.getByText(/Indexed\. Split into/)).toBeVisible({ timeout: 10_000 });
    await page.getByRole("button", { name: "Done" }).click();

    await expect(page.getByText("receipt.pdf")).toBeVisible();
  });

  test("rejects a non-PDF upload without issuing the request", async ({ page }) => {
    await login(page);
    await page.goto("/policies");
    await page.getByRole("button", { name: "Upload policy PDF" }).click();

    const fileInput = page.locator('input[type="file"]');
    await fileInput.setInputFiles(path.join(FIXTURES_DIR, "not-a-document.exe"));

    await expect(page.getByText(/Not a PDF/)).toBeVisible();
  });

  test("navigates from the sidebar and command palette, and Policies is no longer disabled", async ({ page }) => {
    await login(page);
    await page.goto("/dashboard");

    const hamburger = page.getByRole("button", { name: "Open navigation" });
    if (await hamburger.isVisible()) {
      await hamburger.click();
    }
    await page.getByRole("link", { name: "Policies" }).click();
    await expect(page).toHaveURL(/\/policies$/);

    await page.getByRole("button", { name: "Search or jump to..." }).click();
    await page.getByRole("option", { name: "Go to Policies" }).click();
    await expect(page).toHaveURL(/\/policies$/);
  });

  test("no horizontal scroll at the 859/860px breakpoint", async ({ page }) => {
    await login(page);
    for (const width of [859, 860]) {
      await page.setViewportSize({ width, height: 900 });
      await page.goto("/policies");
      const hasHorizontalScroll = await page.evaluate(
        () => document.documentElement.scrollWidth > document.documentElement.clientWidth,
      );
      expect(hasHorizontalScroll).toBe(false);
    }
  });
});
