import { ServiceStatus } from "@/components/service-status";

export default function Home() {
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
