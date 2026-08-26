import * as Sentry from "@sentry/nextjs";

// Empty/unset DSN disables the SDK (its own default) — local dev runs without Sentry.
Sentry.init({
  dsn: process.env.NEXT_PUBLIC_SENTRY_DSN,
  environment: process.env.NEXT_PUBLIC_SENTRY_ENVIRONMENT ?? "local",
  sendDefaultPii: false,
});
