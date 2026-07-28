// `next build` with `output: "standalone"` doesn't copy `public/` or `.next/static` into the
// standalone folder — the Dockerfile does this with `cp -r` for the production image; this is
// the same copy for Playwright's local/CI webServer, without relying on a Unix shell.
import { cpSync, existsSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");

function copyIfExists(from, to) {
  if (existsSync(from)) {
    cpSync(from, to, { recursive: true });
  }
}

copyIfExists(join(root, "public"), join(root, ".next/standalone/public"));
copyIfExists(join(root, ".next/static"), join(root, ".next/standalone/.next/static"));
