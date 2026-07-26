"use client";

import { useEffect, useState } from "react";
import { checkHealth, type HealthState } from "@/lib/health";

interface ServiceStatusProps {
  label: string;
  url: string;
}

const STATE_STYLES: Record<HealthState, string> = {
  checking: "bg-zinc-300 dark:bg-zinc-600",
  up: "bg-green-500",
  down: "bg-red-500",
};

const STATE_LABELS: Record<HealthState, string> = {
  checking: "Checking…",
  up: "Healthy",
  down: "Unreachable",
};

export function ServiceStatus({ label, url }: ServiceStatusProps) {
  const [state, setState] = useState<HealthState>("checking");

  useEffect(() => {
    let cancelled = false;

    async function poll() {
      const result = await checkHealth(url);
      if (!cancelled) {
        setState(result);
      }
    }

    poll();
    const interval = setInterval(poll, 5000);

    return () => {
      cancelled = true;
      clearInterval(interval);
    };
  }, [url]);

  return (
    <div
      data-testid={`service-status-${label.toLowerCase()}`}
      className="flex items-center gap-3 rounded-lg border border-zinc-200 px-4 py-3 dark:border-zinc-800"
    >
      <span
        className={`h-3 w-3 shrink-0 rounded-full ${STATE_STYLES[state]}`}
        aria-hidden
      />
      <div className="flex flex-col">
        <span className="font-medium text-zinc-950 dark:text-zinc-50">{label}</span>
        <span className="text-sm text-zinc-600 dark:text-zinc-400">
          {STATE_LABELS[state]}
        </span>
      </div>
    </div>
  );
}
