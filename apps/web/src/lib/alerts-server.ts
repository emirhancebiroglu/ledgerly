import { apiFetchAuthenticated } from "@/lib/api-server";
import { parseAlert, type Alert, type AlertType } from "@/lib/alerts";

export interface ListAlertsOptions {
  type?: AlertType;
  page?: number;
  size?: number;
}

export async function listAlerts(
  options: ListAlertsOptions = {},
): Promise<{ ok: true; alerts: Alert[] } | { ok: false }> {
  const params = new URLSearchParams();
  params.set("page", String(options.page ?? 0));
  params.set("size", String(options.size ?? 100));
  if (options.type) {
    params.set("type", options.type);
  }
  const response = await apiFetchAuthenticated(`/api/v1/alerts?${params.toString()}`);
  if (!response.ok) {
    return { ok: false };
  }
  const payload = (await response.json()) as Parameters<typeof parseAlert>[0][];
  return { ok: true, alerts: payload.map(parseAlert) };
}

export async function unreadAlertCount(): Promise<number> {
  const response = await apiFetchAuthenticated("/api/v1/alerts/unread-count");
  if (!response.ok) {
    return 0;
  }
  const payload = (await response.json()) as { unreadCount: number };
  return payload.unreadCount;
}
