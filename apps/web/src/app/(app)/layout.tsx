import { AppShell } from "@/components/shell/app-shell";
import { getDashboardSummary } from "@/lib/dashboard";
import { getCurrentUser } from "@/lib/me";
import { unreadAlertCount } from "@/lib/alerts-server";

/**
 * Identity comes from the authenticated `/me` projection. Keep the neutral fallback only for an
 * unavailable API, never derive a display identity from opaque JWT UUIDs.
 */
const ORG_NAME_PLACEHOLDER = "Your organization";
const ORG_INITIAL_PLACEHOLDER = "O";
const USER_INITIAL_PLACEHOLDER = "U";

export default async function AppLayout({ children }: { children: React.ReactNode }) {
  const [summary, currentUser, unreadAlerts] = await Promise.all([
    getDashboardSummary(),
    getCurrentUser(),
    unreadAlertCount(),
  ]);
  const orgName = currentUser?.organizationName ?? ORG_NAME_PLACEHOLDER;
  const userInitial = currentUser?.fullName.charAt(0).toUpperCase() ?? USER_INITIAL_PLACEHOLDER;

  return (
    <AppShell
      reviewQueueCount={summary?.reviewQueueCount}
      unreadAlertCount={unreadAlerts}
      orgName={orgName}
      orgInitial={orgName.charAt(0).toUpperCase() || ORG_INITIAL_PLACEHOLDER}
      userInitial={userInitial}
    >
      {children}
    </AppShell>
  );
}
