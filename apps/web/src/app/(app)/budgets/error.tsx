"use client";

import { Button } from "@/components/ui/button";

export default function BudgetsError({ reset }: { error: Error; reset: () => void }) {
  return <div className="max-w-[1080px] p-6 md:p-8"><div role="alert" className="rounded-xl border border-danger/30 bg-danger-soft p-6 text-sm text-danger">Couldn&apos;t load budgets. <Button variant="link" className="h-auto p-0 text-danger" onClick={reset}>Try again</Button></div></div>;
}
