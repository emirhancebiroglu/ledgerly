import { AppShell } from "@/components/shell/app-shell";
import { getDashboardSummary } from "@/lib/dashboard";

/**
 * The org name and user initial the sidebar/topbar want are not available from any endpoint
 * today — the access token carries only `userId`/`organizationId` (both opaque UUIDs), and
 * nothing in the API exposes a display name for either (`AuthenticatedPrincipal`,
 * `JwtService`). Placeholders until a `/api/v1/me`-shaped endpoint exists; tracked as a known
 * gap rather than guessed at from the UUID.
 */
const ORG_NAME_PLACEHOLDER = "Your organization";
const ORG_INITIAL_PLACEHOLDER = "O";
const USER_INITIAL_PLACEHOLDER = "U";

export default async function AppLayout({ children }: { children: React.ReactNode }) {
  const summary = await getDashboardSummary();

  return (
    <AppShell
      reviewQueueCount={summary?.reviewQueueCount}
      orgName={ORG_NAME_PLACEHOLDER}
      orgInitial={ORG_INITIAL_PLACEHOLDER}
      userInitial={USER_INITIAL_PLACEHOLDER}
    >
      {children}
    </AppShell>
  );
}
