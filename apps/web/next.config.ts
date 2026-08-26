import type { NextConfig } from "next";
import { withSentryConfig } from "@sentry/nextjs";

const nextConfig: NextConfig = {
  output: "standalone",
};

export default withSentryConfig(nextConfig, {
  org: "emirhancebiroglu",
  project: "ledgerly-web",
  // Source-map upload needs SENTRY_AUTH_TOKEN, which isn't set anywhere in this repo — quiet
  // rather than a build-time warning on every `next build` that doesn't have it.
  silent: true,
});
