"use client";

import { Button } from "@/components/ui/button";

export default function AlertsError({ reset }: { error: Error; reset: () => void }) {
  return (
    <div className="max-w-[820px] p-6 md:p-8">
      <div role="alert" className="rounded-xl border border-danger/30 bg-danger-soft p-6 text-sm text-danger">
        Couldn&apos;t load alerts.{" "}
        <Button variant="link" className="h-auto p-0 text-danger" onClick={reset}>
          Try again
        </Button>
      </div>
    </div>
  );
}
