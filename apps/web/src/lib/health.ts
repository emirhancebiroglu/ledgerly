export type HealthState = "checking" | "up" | "down";

const FETCH_TIMEOUT_MS = 3000;

export async function checkHealth(url: string): Promise<HealthState> {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), FETCH_TIMEOUT_MS);

  try {
    const response = await fetch(url, { signal: controller.signal });
    return response.ok ? "up" : "down";
  } catch {
    return "down";
  } finally {
    clearTimeout(timeout);
  }
}
