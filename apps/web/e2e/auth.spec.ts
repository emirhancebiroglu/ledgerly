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

test("the root path is an entry point, never a screen of its own", async ({ page }) => {
  // Signed out: `/` resolves to login rather than serving the old service-health screen.
  await page.goto("/");
  await expect(page).toHaveURL(/\/login$/);
  await expect(page.getByText(/service health/i)).toHaveCount(0);

  // Signed in: the same URL resolves to the dashboard.
  await page.getByLabel("Work email").fill("elif@example.com");
  await page.getByLabel("Password", { exact: true }).fill("Correct-Horse-Battery9");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/dashboard/);

  await page.goto("/");
  await expect(page).toHaveURL(/\/dashboard/);
});

test("no product route renders without a session", async ({ page }) => {
  for (const path of ["/dashboard", "/expenses", "/upload", "/review", "/budgets"]) {
    await page.goto(path);
    await expect(page).toHaveURL(`/login?next=${encodeURIComponent(path)}`);
  }
});

test("autofilled credentials keep the designed field styling inside the wrapper", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto("/login");

  const email = page.getByLabel("Work email");
  const password = page.getByLabel("Password", { exact: true });

  // Chrome only applies `:-webkit-autofill` to a genuine autofill, which Playwright cannot
  // trigger. Assert the rule that governs it is actually served and would repaint the field,
  // rather than asserting a state the harness can't produce.
  const autofillRule = await page.evaluate(() => {
    // The rule lives inside `@layer base`, whose contents are nested rules rather than entries in
    // the sheet's top-level `cssRules`, so the search has to recurse into grouping rules.
    function find(rules: CSSRuleList): CSSStyleRule | null {
      for (const rule of Array.from(rules)) {
        if (rule instanceof CSSStyleRule && rule.selectorText?.includes("-webkit-autofill")) {
          return rule;
        }
        const nested = (rule as CSSGroupingRule).cssRules;
        if (nested) {
          const hit = find(nested);
          if (hit) return hit;
        }
      }
      return null;
    }

    for (const sheet of Array.from(document.styleSheets)) {
      let rule: CSSStyleRule | null;
      try {
        rule = find(sheet.cssRules);
      } catch {
        continue; // cross-origin sheet
      }
      if (rule) {
        return {
          textFillColor: rule.style.getPropertyValue("-webkit-text-fill-color"),
          boxShadow: rule.style.getPropertyValue("box-shadow"),
          backgroundClip:
            rule.style.getPropertyValue("background-clip") ||
            rule.style.getPropertyValue("-webkit-background-clip"),
        };
      }
    }
    return null;
  });

  expect(autofillRule).not.toBeNull();
  expect(autofillRule!.textFillColor).not.toBe("");
  expect(autofillRule!.boxShadow).toContain("inset");
  expect(autofillRule!.backgroundClip).toBe("text");

  // A filled field must stay inside its wrapper's rounded border at every viewport.
  for (const width of [1440, 390]) {
    await page.setViewportSize({ width, height: 900 });
    await email.fill("a-fairly-long-address@example.com");
    await password.fill("a-long-enough-password");

    for (const field of [email, password]) {
      const fieldBox = (await field.boundingBox())!;
      const wrapperBox = (await field.locator("xpath=..").boundingBox())!;

      expect(fieldBox.x).toBeGreaterThanOrEqual(wrapperBox.x - 1);
      expect(fieldBox.x + fieldBox.width).toBeLessThanOrEqual(wrapperBox.x + wrapperBox.width + 1);
      expect(fieldBox.y).toBeGreaterThanOrEqual(wrapperBox.y - 1);
      expect(fieldBox.y + fieldBox.height).toBeLessThanOrEqual(wrapperBox.y + wrapperBox.height + 1);
    }
  }

  // Focus still lifts the wrapper's border and ring, not the bare input.
  const wrapper = email.locator("xpath=..");
  await email.focus();
  const focusStyles = await wrapper.evaluate((node) => {
    const style = getComputedStyle(node);
    return { borderColor: style.borderColor, boxShadow: style.boxShadow };
  });
  expect(focusStyles.borderColor).not.toBe("");
  expect(focusStyles.boxShadow).not.toBe("none");
});
