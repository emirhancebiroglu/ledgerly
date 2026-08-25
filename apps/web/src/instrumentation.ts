import { captureRequestError } from "@sentry/nextjs";

export async function register() {
  if (process.env.NEXT_RUNTIME === "nodejs") {
    await import("./sentry.server.config");
  }

  if (process.env.NEXT_RUNTIME === "edge") {
    await import("./sentry.edge.config");
  }
}

// captureRequestError itself no-ops when the SDK was never initialized (empty DSN) — safe to
// wire unconditionally rather than branching on NEXT_PUBLIC_SENTRY_DSN here too.
export const onRequestError = captureRequestError;
