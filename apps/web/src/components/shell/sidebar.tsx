"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { ChevronsUpDown, Settings } from "lucide-react";
import { cn } from "@/lib/utils";
import { LogoMark } from "@/components/shell/logo-mark";
import { NAV_ITEMS, DISABLED_NAV_ITEMS } from "@/components/shell/nav-config";
import { Badge } from "@/components/ui/badge";

interface SidebarProps {
  reviewQueueCount?: number;
  orgName: string;
  orgInitial: string;
  className?: string;
  onNavigate?: () => void;
}

export function Sidebar({
  reviewQueueCount,
  orgName,
  orgInitial,
  className,
  onNavigate,
}: SidebarProps) {
  const pathname = usePathname();

  return (
    <nav
      aria-label="Main"
      className={cn(
        "flex w-60 shrink-0 flex-col gap-0.5 border-r border-sidebar-border bg-sidebar p-3",
        className,
      )}
    >
      <div className="flex items-center gap-2 px-2 pt-1.5 pb-5">
        <LogoMark />
        <span className="text-[15px] font-semibold tracking-tight">Ledgerly</span>
      </div>

      <div className="flex flex-col gap-0.5">
        {NAV_ITEMS.map((item) => {
          const isActive = pathname.startsWith(item.href);
          const count = item.countKey === "reviewQueue" ? reviewQueueCount : undefined;
          return (
            <Link
              key={item.href}
              href={item.href}
              onClick={onNavigate}
              aria-current={isActive ? "page" : undefined}
              className={cn(
                "flex min-h-[34px] items-center gap-2.5 rounded-lg px-2.5 py-2 text-[13.5px] font-medium transition-colors",
                isActive
                  ? "bg-sidebar-accent text-sidebar-accent-foreground"
                  : "text-sidebar-foreground hover:bg-sidebar-accent/60",
              )}
            >
              <item.icon className="size-[17px]" strokeWidth={1.8} aria-hidden />
              <span className="whitespace-nowrap">{item.label}</span>
              {!!count && (
                <Badge
                  variant="outline"
                  className="ml-auto shrink-0 rounded-full border-transparent bg-warning-soft px-1.5 py-0 text-[11px] font-semibold text-warning-foreground"
                >
                  {count}
                </Badge>
              )}
            </Link>
          );
        })}

        <div className="mx-1 my-2.5 h-px bg-sidebar-border" />

        {DISABLED_NAV_ITEMS.map((item) => (
          <div
            key={item.label}
            aria-disabled="true"
            title="Coming in a later milestone"
            className="flex cursor-not-allowed items-center gap-2.5 rounded-lg px-2.5 py-2 text-[13.5px] font-medium text-muted-foreground/70"
          >
            <item.icon className="size-[17px]" strokeWidth={1.8} aria-hidden />
            <span className="whitespace-nowrap">{item.label}</span>
          </div>
        ))}
      </div>

      <div className="flex-1" />

      <div
        aria-disabled="true"
        title="Coming in a later milestone"
        className="flex cursor-not-allowed items-center gap-2.5 rounded-lg px-2.5 py-2 text-[13.5px] font-medium text-muted-foreground"
      >
        <Settings className="size-[17px]" strokeWidth={1.8} aria-hidden />
        <span>Settings</span>
      </div>

      {/* Not wired to anything: there is no multi-org membership in the data model yet (a user
          belongs to exactly one organization), so there is nothing to switch to. Disabled like
          Budgets/Alerts/Policies rather than left as a button that looks interactive and isn't. */}
      <div
        aria-disabled="true"
        title="Organization switching isn't available yet"
        className="mt-1.5 flex cursor-not-allowed items-center gap-2 border-t border-sidebar-border px-2 pt-2.5 pb-1"
      >
        <div className="flex size-6 shrink-0 items-center justify-center rounded-md bg-accent text-[11px] font-bold text-accent-foreground">
          {orgInitial}
        </div>
        <div className="min-w-0 flex-1">
          <div className="truncate text-[12.5px] font-semibold">{orgName}</div>
        </div>
        <ChevronsUpDown className="size-3.5 shrink-0 text-muted-foreground" aria-hidden />
      </div>
    </nav>
  );
}
