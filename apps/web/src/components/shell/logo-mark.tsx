export function LogoMark({ className }: { className?: string }) {
  return (
    <div
      className={`flex size-6 shrink-0 items-center justify-center rounded-[7px] bg-primary ${className ?? ""}`}
    >
      <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden>
        <path
          d="M3 3.5C3 2.67 3.67 2 4.5 2H11l2 2v8.5c0 .83-.67 1.5-1.5 1.5h-7C3.67 14 3 13.33 3 12.5v-9z"
          fill="white"
          fillOpacity="0.92"
        />
        <path
          d="M5.5 6.5h5M5.5 8.5h5M5.5 10.5h3"
          stroke="var(--primary)"
          strokeWidth="1"
          strokeLinecap="round"
        />
      </svg>
    </div>
  );
}
