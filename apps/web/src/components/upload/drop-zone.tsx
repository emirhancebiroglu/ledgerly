"use client";

import { useRef, useState, type DragEvent } from "react";
import { Upload } from "lucide-react";
import { cn } from "@/lib/utils";

interface DropZoneProps {
  onFileSelected: (file: File) => void;
  disabled?: boolean;
}

export function DropZone({ onFileSelected, disabled }: DropZoneProps) {
  const [isDragOver, setIsDragOver] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);

  function handleDrop(event: DragEvent<HTMLDivElement>) {
    event.preventDefault();
    setIsDragOver(false);
    const file = event.dataTransfer.files[0];
    if (file) {
      onFileSelected(file);
    }
  }

  return (
    <div
      role="button"
      tabIndex={disabled ? -1 : 0}
      aria-disabled={disabled}
      onClick={() => !disabled && inputRef.current?.click()}
      onKeyDown={(event) => {
        if (!disabled && (event.key === "Enter" || event.key === " ")) {
          event.preventDefault();
          inputRef.current?.click();
        }
      }}
      onDragOver={(event) => {
        event.preventDefault();
        if (!disabled) setIsDragOver(true);
      }}
      onDragLeave={() => setIsDragOver(false)}
      onDrop={disabled ? undefined : handleDrop}
      className={cn(
        "cursor-pointer rounded-2xl border-[1.5px] border-dashed border-border bg-card px-6 py-12 text-center transition-all",
        isDragOver && "border-primary bg-accent-soft",
        disabled && "cursor-not-allowed opacity-60",
      )}
    >
      <input
        ref={inputRef}
        type="file"
        accept="application/pdf,image/png,image/jpeg"
        className="sr-only"
        onChange={(event) => {
          const file = event.target.files?.[0];
          if (file) {
            onFileSelected(file);
          }
          event.target.value = "";
        }}
        disabled={disabled}
      />
      <Upload className="mx-auto mb-3.5 size-8 text-primary" strokeWidth={1.6} aria-hidden />
      <div className="text-[14px] font-semibold">Drop a receipt or invoice</div>
      <div className="mt-1 text-[12.5px] text-muted-foreground">
        PDF, PNG or JPG · click to browse
      </div>
    </div>
  );
}
