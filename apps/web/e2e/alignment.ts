import { expect, type Page } from "@playwright/test";

/** Matches `--breakpoint-shell` in globals.css — below it the rows deliberately stack instead of
 * forming columns, so there is nothing to align. */
const SHELL_BREAKPOINT = 860;

/**
 * Asserts that the amount and status cells of a row list form real columns.
 *
 * The defect this guards against: with `auto` grid tracks each cell sized to its own row's
 * content, so a row carrying the wider "Needs review" chip pushed that row's amount left and the
 * figures stopped sharing an edge. Measuring geometry is the only honest check — the class list
 * alone cannot tell you what the browser laid out.
 */
export async function expectAlignedAmountAndStatusColumns(page: Page): Promise<void> {
  const viewport = page.viewportSize();
  if (viewport && viewport.width < SHELL_BREAKPOINT) {
    throw new Error(
      `Column alignment only applies at or above ${SHELL_BREAKPOINT}px; got ${viewport.width}px`,
    );
  }

  const amounts = page.getByTestId("expense-amount");
  const chips = page.getByTestId("expense-status");
  const categories = page.getByTestId("expense-category");

  await expect(amounts.first()).toBeVisible();
  const rowCount = await amounts.count();
  expect(rowCount).toBeGreaterThan(1);
  expect(await chips.count()).toBe(rowCount);
  expect(await categories.count()).toBe(rowCount);

  const amountRightEdges: number[] = [];
  const chipLeftEdges: number[] = [];
  const categoryLeftEdges: number[] = [];
  const statusLabels = new Set<string>();

  for (let index = 0; index < rowCount; index += 1) {
    const amountBox = await amounts.nth(index).boundingBox();
    const chipBox = await chips.nth(index).boundingBox();
    const categoryBox = await categories.nth(index).boundingBox();
    if (!amountBox || !chipBox || !categoryBox) {
      throw new Error(`Row ${index} has no laid-out amount, status or category cell`);
    }

    amountRightEdges.push(amountBox.x + amountBox.width);
    chipLeftEdges.push(chipBox.x);
    categoryLeftEdges.push(categoryBox.x);
    statusLabels.add((await chips.nth(index).innerText()).trim());
  }

  // Without at least two different chip widths in the sample, alignment proves nothing.
  expect(statusLabels.size).toBeGreaterThan(1);

  // Sub-pixel rounding is tolerable; anything more means the track is still content-sized.
  expect(Math.max(...amountRightEdges) - Math.min(...amountRightEdges)).toBeLessThanOrEqual(1);
  expect(Math.max(...chipLeftEdges) - Math.min(...chipLeftEdges)).toBeLessThanOrEqual(1);
  // The category column was the other half of the reported misalignment: it has to start on one
  // edge no matter how long the vendor name beside it is.
  expect(Math.max(...categoryLeftEdges) - Math.min(...categoryLeftEdges)).toBeLessThanOrEqual(1);
}
