"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { Dialog as DialogPrimitive } from "@base-ui/react/dialog";
import { Search } from "lucide-react";
import { QUICK_JUMP_ITEMS } from "@/components/shell/nav-config";
import { cn } from "@/lib/utils";

interface CommandPaletteProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

export function CommandPalette({ open, onOpenChange }: CommandPaletteProps) {
  // Tracks (previous `open`, a counter) in state rather than a ref, per React's documented
  // pattern for adjusting state during render in response to a prop change. The counter only
  // increments on a closed→open transition, never on close, so `PaletteBody` remounts (resetting
  // activeIndex to row 0) each time the palette opens, but stays mounted through the close
  // animation instead of tearing down and re-running `autoFocus`, which was racing the parent's
  // manual focus-restoration on Escape/close.
  const [state, setState] = useState({ wasOpen: false, openCount: 0 });
  if (open !== state.wasOpen) {
    setState({
      wasOpen: open,
      openCount: open && !state.wasOpen ? state.openCount + 1 : state.openCount,
    });
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
          <PaletteBody key={state.openCount} onNavigate={() => onOpenChange(false)} />
        </DialogPrimitive.Popup>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  );
}

function PaletteBody({ onNavigate }: { onNavigate: () => void }) {
  const router = useRouter();
  const [activeIndex, setActiveIndex] = useState(0);
  const [query, setQuery] = useState("");
  const items = QUICK_JUMP_ITEMS.filter((item) => item.label.toLowerCase().includes(query.toLowerCase()));

  function go(href: string) {
    onNavigate();
    router.push(href);
  }

  function onInputKeyDown(event: React.KeyboardEvent<HTMLInputElement>) {
    if (event.key === "ArrowDown") {
      event.preventDefault();
      setActiveIndex((index) => (index + 1) % Math.max(items.length, 1));
    } else if (event.key === "ArrowUp") {
      event.preventDefault();
      setActiveIndex((index) => (index - 1 + Math.max(items.length, 1)) % Math.max(items.length, 1));
    } else if (event.key === "Enter" && items[activeIndex]) {
      event.preventDefault();
      go(items[activeIndex].href);
    }
  }

  return (
    <>
      <div className="flex items-center gap-2.5 border-b border-border px-4 py-3.5">
        <Search className="size-4 shrink-0 text-muted-foreground" aria-hidden />
        <input
          autoFocus
          role="combobox"
          aria-expanded="true"
          aria-controls="palette-listbox"
          aria-activedescendant={`palette-option-${activeIndex}`}
          placeholder="Jump to a page or expense..."
          onKeyDown={onInputKeyDown}
          onChange={(event) => { setQuery(event.target.value); setActiveIndex(0); }}
          className="flex-1 border-none text-sm outline-none placeholder:text-muted-foreground"
        />
      </div>
      <div id="palette-listbox" className="p-2" role="listbox" aria-label="Quick jump">
        {items.length === 0 ? <p className="px-3 py-5 text-sm text-muted-foreground">No matching pages.</p> : items.map((item, index) => (
          <button
            key={item.href}
            id={`palette-option-${index}`}
            type="button"
            role="option"
            aria-selected={index === activeIndex}
            onMouseEnter={() => setActiveIndex(index)}
            onClick={() => go(item.href)}
            className={cn(
              "w-full rounded-lg px-3 py-2.5 text-left text-[13px]",
              index === activeIndex && "bg-muted",
            )}
          >
            {item.label}
          </button>
        ))}
      </div>
    </>
  );
}
