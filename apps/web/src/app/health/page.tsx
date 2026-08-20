import { ServiceStatus } from "@/components/service-status";

/**
 * Operational view of the two backing services. It lives here rather than at `/`, which is an
 * authenticated entry point, and it stays behind the session guard like every other non-public
 * route — service topology and liveness are not facts to hand an anonymous visitor.
 *
 * The checks run in the browser against each service's own health endpoint, so this reports what
 * a client can actually reach rather than what the Next.js server can.
 */
export default function HealthPage() {
  const apiUrl = `${process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080"}/actuator/health`;
  const aiUrl = `${process.env.NEXT_PUBLIC_AI_URL ?? "http://localhost:8000"}/health`;

  return (
    <div className="flex flex-1 flex-col items-center bg-zinc-50 px-6 py-16 dark:bg-black">
      <main className="flex w-full max-w-xl flex-col gap-6">
        <h1 className="text-2xl font-semibold text-zinc-950 dark:text-zinc-50">
          Ledgerly service health
        </h1>
        <div className="flex flex-col gap-3">
          <ServiceStatus label="Api" url={apiUrl} />
          <ServiceStatus label="Ai" url={aiUrl} />
        </div>
      </main>
    </div>
  );
}
