export default function AlertsLoading() {
  return (
    <div className="max-w-[820px] p-6 md:p-8">
      <div className="h-7 w-28 animate-pulse rounded bg-muted" />
      <div className="mt-5 flex flex-col gap-3">
        <div className="h-24 animate-pulse rounded-xl bg-muted" />
        <div className="h-24 animate-pulse rounded-xl bg-muted" />
        <div className="h-24 animate-pulse rounded-xl bg-muted" />
      </div>
    </div>
  );
}
