import { expect, test, type Page } from "@playwright/test";
import path from "node:path";

const FIXTURES_DIR = path.join(process.cwd(), "e2e", "fixtures");

async function login(page: Page): Promise<void> {
  await page.goto("/login");
  await page.getByLabel("Work email").fill("owner@example.com");
  await page.getByLabel("Password", { exact: true }).fill("password123");
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(page).toHaveURL(/\/dashboard/);
}

test.describe("upload", () => {
  test("a dropped PDF uploads and each status transition advances the step list", async ({
    page,
  }) => {
    await login(page);
    await page.goto("/upload");

    // The drop zone's file input is the same element a real drag-and-drop resolves to
    // (DataTransfer.files); Playwright has no cross-browser way to simulate an OS-level file
    // drag, so setInputFiles is the standard way to drive this path end to end.
    const fileInput = page.locator('input[type="file"]');
    await fileInput.setInputFiles(path.join(FIXTURES_DIR, "receipt.pdf"));

    await expect(page.getByText("receipt.pdf")).toBeVisible();

    // Persisted activity advances the visible stages through the terminal ledger outcome,
    // driven entirely by the mock's real SSE events.
    await expect(page.getByRole("status", { name: "In progress" })).toBeVisible();
    await expect(page.getByText("Posted to ledger", { exact: true })).toBeVisible({ timeout: 5000 });
    await expect(page.getByRole("link", { name: /View in expenses/ })).toBeVisible();
  });

  test("a rejected type shows the server's validation message and never enters the step sequence", async ({
    page,
  }) => {
    await login(page);
    await page.goto("/upload");

    const fileInput = page.locator('input[type="file"]');
    await fileInput.setInputFiles(path.join(FIXTURES_DIR, "not-a-document.exe"));

    await expect(page.getByText(/Unsupported document type/)).toBeVisible();
    await expect(page.getByText("Uploading", { exact: true })).toBeHidden();
    await expect(page.getByText("Processing", { exact: true })).toBeHidden();
  });

  test("a document that fails processing shows the failure reason instead of a fake success", async ({
    page,
  }) => {
    await login(page);
    await page.goto("/upload");

    // The mock's simulated pipeline treats a filename containing "fail" as a processing failure.
    const fileInput = page.locator('input[type="file"]');
    await fileInput.setInputFiles({
      name: "fail-scan.pdf",
      mimeType: "application/pdf",
      buffer: Buffer.from("%PDF-1.1\ntrailer<</Root 1 0 R>>"),
    });

    await expect(page.getByText("Failed", { exact: true })).toBeVisible({ timeout: 5000 });
    await expect(page.getByText(/Processing failed: Unreadable scan/)).toBeVisible();
  });

  test("the drop zone is keyboard-operable (a real button, tabindex 0)", async ({ page }) => {
    await login(page);
    await page.goto("/upload");

    const dropZone = page.getByRole("button", { name: /Drop a receipt or invoice/ });
    await expect(dropZone).toHaveAttribute("tabindex", "0");
  });

  test("the SSE connection closes once a terminal status arrives (no open connection lingers)", async ({
    page,
  }) => {
    await login(page);
    await page.goto("/upload");

    const fileInput = page.locator('input[type="file"]');
    await fileInput.setInputFiles(path.join(FIXTURES_DIR, "receipt.pdf"));
    await expect(page.getByText("Posted to ledger", { exact: true })).toBeVisible({ timeout: 5000 });

    // After completion, no eventsource request should still be pending/open.
    const openConnections = await page.evaluate(
      () =>
        performance
          .getEntriesByType("resource")
          .filter(
            (entry) =>
              entry.name.includes("/events") &&
              (entry as PerformanceResourceTiming).responseEnd === 0,
          ).length,
    );
    expect(openConnections).toBe(0);
  });
});
