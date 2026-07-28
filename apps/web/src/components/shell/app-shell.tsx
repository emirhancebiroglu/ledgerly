"use client";

import { useEffect, useRef, useState } from "react";
import { usePathname } from "next/navigation";
import { Sidebar } from "@/components/shell/sidebar";
import { Topbar } from "@/components/shell/topbar";
import { CommandPalette } from "@/components/shell/command-palette";
import { MobileNavDrawer } from "@/components/shell/mobile-nav-drawer";
import { NAV_ITEMS } from "@/components/shell/nav-config";

interface AppShellProps {
  children: React.ReactNode;
  reviewQueueCount?: number;
  orgName: string;
  orgInitial: string;
  userInitial: string;
}

function pageTitleFor(pathname: string): string {
  const match = NAV_ITEMS.find((item) => pathname.startsWith(item.href));
  return match?.label ?? "Ledgerly";
}

export function AppShell({
  children,
  reviewQueueCount,
  orgName,
  orgInitial,
  userInitial,
}: AppShellProps) {
  const pathname = usePathname();
  const [paletteOpen, setPaletteOpen] = useState(false);
  const [mobileNavOpen, setMobileNavOpen] = useState(false);
  const paletteTriggerRef = useRef<HTMLButtonElement>(null);
  const paletteReturnFocusRef = useRef<HTMLElement | null>(null);

  function openPalette() {
    // Base UI's Dialog only knows how to return focus to a <Dialog.Trigger>-rendered element;
    // since the palette opens both from a click and from a global ⌘K listener with no trigger
    // element in the loop, the return target is tracked here instead.
    paletteReturnFocusRef.current = document.activeElement as HTMLElement | null;
    setPaletteOpen(true);
  }

  function handlePaletteOpenChange(open: boolean) {
    setPaletteOpen(open);
    if (!open) {
      (paletteReturnFocusRef.current ?? paletteTriggerRef.current)?.focus();
    }
  }

  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      if (event.key.toLowerCase() === "k" && (event.metaKey || event.ctrlKey)) {
        event.preventDefault();
        if (paletteOpen) {
          handlePaletteOpenChange(false);
        } else {
          openPalette();
        }
      }
    }
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [paletteOpen]);

  return (
    <div className="flex h-dvh w-full overflow-hidden">
      <Sidebar
        reviewQueueCount={reviewQueueCount}
        orgName={orgName}
        orgInitial={orgInitial}
        className="hidden shell:flex"
      />

      <div className="flex min-w-0 flex-1 flex-col">
        <Topbar
          pageTitle={pageTitleFor(pathname)}
          userInitial={userInitial}
          onOpenMobileNav={() => setMobileNavOpen(true)}
          onOpenPalette={openPalette}
          paletteTriggerRef={paletteTriggerRef}
        />
        <main className="flex-1 overflow-auto">
          {/* Keyed by pathname so the entry animation replays per navigation (a fresh mount),
              matching the handoff's "card content fades/slides up on view entry" — the global
              prefers-reduced-motion rule in globals.css collapses this to an instant, no-motion
              state change rather than disabling it outright. */}
          <div key={pathname} className="animate-in fade-in slide-in-from-bottom-1 duration-300">
            {children}
          </div>
        </main>
      </div>

      <MobileNavDrawer
        open={mobileNavOpen}
        onOpenChange={setMobileNavOpen}
        reviewQueueCount={reviewQueueCount}
        orgName={orgName}
        orgInitial={orgInitial}
      />
      <CommandPalette open={paletteOpen} onOpenChange={handlePaletteOpenChange} />
    </div>
  );
}
