import { expect, test } from "@playwright/test";

test("login preserves the designed auth affordances without dead routes", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto("/login");

  await expect(page.getByText("From receipt to a review-ready ledger entry.")).toBeVisible();
  await expect(page.getByRole("button", { name: /Google, coming soon/i })).toBeDisabled();
  await expect(page.getByRole("button", { name: /SSO, coming soon/i })).toBeDisabled();
  await expect(page.getByRole("link", { name: /forgot|terms|privacy/i })).toHaveCount(0);

  const password = page.getByLabel("Password", { exact: true });
  await expect(password).toHaveAttribute("type", "password");
  await page.getByRole("button", { name: "Show password" }).click();
  await expect(password).toHaveAttribute("type", "text");
});

test("registration sends the full identity contract and remains usable on mobile", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/register");

  await expect(page.getByText("From receipt to a review-ready ledger entry.")).toBeHidden();
  await page.getByLabel("Full name").fill("Elif Kaya");
  await page.getByLabel("Company").fill("Northwind Co.");
  await page.getByLabel("Work email").fill("elif@example.com");
  await page.getByLabel("Password", { exact: true }).fill("Correct-Horse-Battery9");
  await expect(page.getByText("Strong password")).toBeVisible();
  await page.getByRole("button", { name: "Create workspace" }).click();

  await expect(page).toHaveURL(/\/dashboard/);
});
