"use client";

import { Dialog as DialogPrimitive } from "@base-ui/react/dialog";
import { Sidebar } from "@/components/shell/sidebar";

interface MobileNavDrawerProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  reviewQueueCount?: number;
  orgName: string;
  orgInitial: string;
}

export function MobileNavDrawer({
  open,
  onOpenChange,
  reviewQueueCount,
  orgName,
  orgInitial,
}: MobileNavDrawerProps) {
  return (
    <DialogPrimitive.Root open={open} onOpenChange={onOpenChange}>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Backdrop className="fixed inset-0 z-[55] bg-foreground/20 duration-150 data-open:animate-in data-open:fade-in-0 data-closed:animate-out data-closed:fade-out-0" />
        <DialogPrimitive.Popup className="fixed inset-y-0 left-0 z-[60] outline-none duration-200 data-open:animate-in data-open:slide-in-from-left data-closed:animate-out data-closed:slide-out-to-left">
          <DialogPrimitive.Title className="sr-only">Navigation</DialogPrimitive.Title>
          <DialogPrimitive.Description className="sr-only">
            Site navigation for Ledgerly.
          </DialogPrimitive.Description>
          <Sidebar
            reviewQueueCount={reviewQueueCount}
            orgName={orgName}
            orgInitial={orgInitial}
            className="h-full"
            onNavigate={() => onOpenChange(false)}
          />
        </DialogPrimitive.Popup>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  );
}
