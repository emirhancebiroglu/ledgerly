// `next build` with `output: "standalone"` doesn't copy `public/` or `.next/static` into the
// standalone folder — the Dockerfile does this with `cp -r` for the production image; this is
// the same copy for Playwright's local/CI webServer, without relying on a Unix shell.
import { cpSync, existsSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");

function copyRequired(from, to) {
  if (!existsSync(from)) {
    // Every real build produces this — a missing source means the build layout changed (or
    // didn't run at all), not that there's nothing to copy. Failing loudly here beats a server
    // that boots and silently serves unstyled HTML while every test still passes on markup alone.
    console.error(`copy-standalone-assets: expected build output missing: ${from}`);
    process.exit(1);
  }
  cpSync(from, to, { recursive: true });
}

function copyIfPresent(from, to) {
  if (existsSync(from)) {
    cpSync(from, to, { recursive: true });
  }
}

// `public/` is optional — a from-scratch checkout with no static assets is still a valid build.
copyIfPresent(join(root, "public"), join(root, ".next/standalone/public"));
copyRequired(join(root, ".next/static"), join(root, ".next/standalone/.next/static"));
