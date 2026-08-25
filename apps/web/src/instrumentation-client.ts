import * as Sentry from "@sentry/nextjs";

// Empty/unset DSN disables the SDK (its own default) — local dev runs without Sentry.
Sentry.init({
  dsn: process.env.NEXT_PUBLIC_SENTRY_DSN,
  environment: process.env.NEXT_PUBLIC_SENTRY_ENVIRONMENT ?? "local",
  // No session replay, no performance tracing — this is error tracking only (T6's scope).
  // Replay in particular can capture on-screen text (invoice amounts, vendor names) that
  // never needs to leave the browser for an exception report to be useful.
  sendDefaultPii: false,
  // T8: the default consoleIntegration turns every console.* call into a breadcrumb attached
  // to whatever error fires next. This codebase has no console.* calls today, but a future
  // one (or a library dependency's own console output) would ship unredacted into Sentry with
  // no code-review signal — same reasoning as disabling ai's LoggingIntegration.
  integrations: (defaults) => defaults.filter((integration) => integration.name !== "Console"),
});

// Required by the SDK to attribute an error to the route it happened on — this is error
// attribution, not the performance tracing this file deliberately opts out of above.
export const onRouterTransitionStart = Sentry.captureRouterTransitionStart;
