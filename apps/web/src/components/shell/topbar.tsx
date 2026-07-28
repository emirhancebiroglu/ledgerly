"use client";

import { Menu, Search } from "lucide-react";
import { logout } from "@/app/actions/auth";
import { Button } from "@/components/ui/button";

interface TopbarProps {
  pageTitle: string;
  userInitial: string;
  onOpenMobileNav: () => void;
  onOpenPalette: () => void;
  paletteTriggerRef: React.RefObject<HTMLButtonElement | null>;
}

export function Topbar({
  pageTitle,
  userInitial,
  onOpenMobileNav,
  onOpenPalette,
  paletteTriggerRef,
}: TopbarProps) {
  return (
    <header className="flex h-[60px] shrink-0 items-center justify-between gap-3 border-b border-border px-5 md:px-7">
      <div className="flex min-w-0 items-center gap-3">
        <button
          type="button"
          onClick={onOpenMobileNav}
          aria-label="Open navigation"
          className="shrink-0 rounded-lg p-1.5 hover:bg-muted shell:hidden"
        >
          <Menu className="size-5" aria-hidden />
        </button>
        <h1 className="truncate text-[15px] font-semibold">{pageTitle}</h1>
      </div>

      <div className="flex shrink-0 items-center gap-2.5">
        <button
          ref={paletteTriggerRef}
          type="button"
          onClick={onOpenPalette}
          className="hidden shrink-0 items-center gap-2 rounded-lg border border-border bg-background px-2.5 py-[7px] text-[12.5px] whitespace-nowrap text-muted-foreground transition-all hover:-translate-y-px hover:shadow-sm shell:flex"
        >
          <Search className="size-3.5" aria-hidden />
          <span>Search or jump to...</span>
          <span className="shrink-0 rounded border border-border px-[5px] py-px text-[10.5px] text-muted-foreground">
            ⌘K
          </span>
        </button>
        <button
          type="button"
          onClick={onOpenPalette}
          aria-label="Search or jump to..."
          className="flex size-[30px] shrink-0 items-center justify-center rounded-lg border border-border shell:hidden"
        >
          <Search className="size-[15px] text-muted-foreground" aria-hidden />
        </button>

        <form action={logout}>
          <Button
            type="submit"
            variant="ghost"
            size="icon"
            className="size-[30px] shrink-0 rounded-full bg-accent-soft text-[12px] font-bold text-accent-foreground hover:bg-accent-soft/80"
            aria-label="Log out"
            title="Log out"
          >
            {userInitial}
          </Button>
        </form>
      </div>
    </header>
  );
}
