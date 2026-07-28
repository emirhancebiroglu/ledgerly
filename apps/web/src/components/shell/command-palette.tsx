"use client";

import { useRouter } from "next/navigation";
import { Dialog as DialogPrimitive } from "@base-ui/react/dialog";
import { Search } from "lucide-react";
import { QUICK_JUMP_ITEMS } from "@/components/shell/nav-config";

interface CommandPaletteProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

export function CommandPalette({ open, onOpenChange }: CommandPaletteProps) {
  const router = useRouter();

  function go(href: string) {
    onOpenChange(false);
    router.push(href);
  }

  return (
    <DialogPrimitive.Root open={open} onOpenChange={onOpenChange}>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Backdrop className="fixed inset-0 z-50 bg-foreground/20 duration-150 data-open:animate-in data-open:fade-in-0 data-closed:animate-out data-closed:fade-out-0" />
        <DialogPrimitive.Popup className="fixed top-[14vh] left-1/2 z-50 w-[560px] max-w-[90vw] -translate-x-1/2 overflow-hidden rounded-2xl bg-card shadow-[0_20px_60px_oklch(0.2_0.01_265_/_0.25)] outline-none duration-150 data-open:animate-in data-open:fade-in-0 data-open:zoom-in-95 data-closed:animate-out data-closed:fade-out-0 data-closed:zoom-out-95">
          <DialogPrimitive.Title className="sr-only">
            Search or jump to a page
          </DialogPrimitive.Title>
          <DialogPrimitive.Description className="sr-only">
            Use the arrow keys to navigate and Enter to select.
          </DialogPrimitive.Description>
          <div className="flex items-center gap-2.5 border-b border-border px-4 py-3.5">
            <Search className="size-4 shrink-0 text-muted-foreground" aria-hidden />
            <input
              autoFocus
              placeholder="Jump to a page or expense..."
              className="flex-1 border-none text-sm outline-none placeholder:text-muted-foreground"
            />
          </div>
          <div className="p-2" role="listbox" aria-label="Quick jump">
            {QUICK_JUMP_ITEMS.map((item) => (
              <button
                key={item.href}
                type="button"
                role="option"
                aria-selected="false"
                onClick={() => go(item.href)}
                className="w-full rounded-lg px-3 py-2.5 text-left text-[13px] hover:bg-muted"
              >
                {item.label}
              </button>
            ))}
          </div>
        </DialogPrimitive.Popup>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  );
}
