import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const css = readFileSync(join(__dirname, "..", "globals.css"), "utf-8");

/**
 * Pins the `:root` custom properties to docs/design/m7/README.md's "Design Tokens" section
 * (and the prototype HTML's inline styles, which use the same values) — a drift here silently
 * detunes the whole product's look without any visual test catching it.
 */
function rootValue(name: string): string | undefined {
  const rootBlock = css.match(/:root\s*\{([\s\S]*?)\n\}/)?.[1] ?? "";
  const match = rootBlock.match(new RegExp(`--${name}:\\s*([^;]+);`));
  return match?.[1].trim();
}

describe("M7 dashboard design tokens", () => {
  it("pins the frozen indigo/violet accent — not the prototype's blue-green alternate", () => {
    expect(rootValue("primary")).toBe("oklch(0.5 0.16 265)");
    expect(rootValue("ring")).toBe("oklch(0.5 0.16 265)");
    expect(rootValue("accent")).toBe("oklch(0.95 0.035 265)");
    expect(rootValue("accent-foreground")).toBe("oklch(0.4 0.14 265)");
    // The prototype's rejected alternate accent — must never appear as a root token value.
    expect(css).not.toContain("oklch(0.56 0.11 195)");
  });

  it("pins the neutral scale", () => {
    expect(rootValue("background")).toBe("oklch(0.99 0.002 265)");
    expect(rootValue("sidebar")).toBe("oklch(0.975 0.003 265)");
    expect(rootValue("card")).toBe("oklch(1 0 0)");
    expect(rootValue("border")).toBe("oklch(0.91 0.006 265)");
    expect(rootValue("foreground")).toBe("oklch(0.22 0.02 265)");
    expect(rootValue("muted-foreground")).toBe("oklch(0.55 0.01 265)");
  });

  it("pins the semantic status pairs, colorblind-safe (never color alone elsewhere)", () => {
    expect(rootValue("success")).toBe("oklch(0.6 0.13 145)");
    expect(rootValue("success-soft")).toBe("oklch(0.95 0.04 145)");
    expect(rootValue("warning")).toBe("oklch(0.7 0.14 70)");
    expect(rootValue("warning-soft")).toBe("oklch(0.96 0.05 80)");
    expect(rootValue("danger")).toBe("oklch(0.6 0.16 25)");
    expect(rootValue("danger-soft")).toBe("oklch(0.95 0.05 25)");
  });

  it("pins the card radius and shadow to the handoff's values", () => {
    expect(rootValue("radius")).toBe("0.75rem"); // 12px
    expect(css).toContain(
      "--shadow-card: 0 1px 2px oklch(0.2 0.02 265 / 0.04), 0 8px 20px oklch(0.2 0.02 265 / 0.03);",
    );
  });

  it("introduces no dark-theme values for the new tokens (frozen light-only at planning)", () => {
    const darkBlock = css.match(/\.dark\s*\{([\s\S]*?)\n\}/)?.[1] ?? "";
    expect(darkBlock).not.toContain("0.16 265");
    expect(darkBlock).not.toContain("--success");
    expect(darkBlock).not.toContain("--warning");
    expect(darkBlock).not.toContain("--danger");
  });

  it("wires font-sans to the actual Geist variable, not a self-referential no-op", () => {
    expect(css).toContain("--font-sans: var(--font-geist-sans);");
    expect(css).toContain("--font-mono: var(--font-geist-mono);");
  });
});
