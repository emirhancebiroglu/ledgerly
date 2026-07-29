import { FileText } from "lucide-react";

interface AuthShellProps {
  children: React.ReactNode;
}

export function AuthShell({ children }: AuthShellProps) {
  return (
    <main className="flex min-h-screen w-full bg-background text-foreground">
      <aside className="relative hidden w-[44%] max-w-[620px] shrink-0 flex-col justify-between overflow-hidden bg-[oklch(0.28_0.07_265)] px-12 py-11 text-[oklch(0.97_0.005_265)] min-[900px]:flex">
        <div className="absolute -right-35 -top-35 size-[420px] rounded-full bg-[oklch(0.42_0.13_265_/_0.55)] blur-3xl" />
        <div className="absolute -bottom-45 -left-30 size-[380px] rounded-full bg-[oklch(0.5_0.12_265_/_0.3)] blur-3xl" />
        <BrandMark inverse />

        <div className="relative max-w-[420px]">
          <h1 className="text-[30px] leading-[1.25] font-semibold tracking-[-0.02em] text-pretty">
            From receipt to a review-ready ledger entry.
          </h1>
          <p className="mt-4 text-sm leading-[1.6] text-[oklch(0.85_0.03_265)] text-pretty">
            Ledgerly extracts document data, categorizes the expense, drafts a balanced posting,
            and routes low-confidence cases to review.
          </p>
          <div className="mt-9 flex gap-8">
            <ProofStat value="100%" label="of posted transactions balanced" />
            <ProofStat value="5 steps" label="visible from upload to outcome" />
          </div>
        </div>

        <p className="relative text-xs text-[oklch(0.78_0.03_265)]">
          Organization-scoped access · Exact minor-unit arithmetic · Auditable agent activity
        </p>
      </aside>

      <section className="flex min-w-0 flex-1 flex-col items-center justify-center px-5 py-8 min-[900px]:px-14 min-[900px]:py-12">
        <div className="w-full max-w-[400px] animate-in fade-in slide-in-from-bottom-2 duration-300">
          <div className="mb-7 min-[900px]:hidden">
            <BrandMark />
          </div>
          {children}
          <p className="mt-7 text-center text-[11.5px] text-muted-foreground min-[900px]:hidden">
            Organization-scoped access · Auditable agent activity
          </p>
        </div>
      </section>
    </main>
  );
}

function BrandMark({ inverse = false }: { inverse?: boolean }) {
  return (
    <div className="relative flex items-center gap-2.5">
      <span
        className={
          inverse
            ? "flex size-[26px] items-center justify-center rounded-[7px] bg-[oklch(0.97_0.005_265)] text-[oklch(0.32_0.1_265)]"
            : "flex size-[26px] items-center justify-center rounded-[7px] bg-primary text-primary-foreground"
        }
      >
        <FileText aria-hidden="true" className="size-[15px] stroke-[1.8]" />
      </span>
      <span className="text-base font-semibold tracking-[-0.01em]">Ledgerly</span>
    </div>
  );
}

function ProofStat({ value, label }: { value: string; label: string }) {
  return (
    <div className="max-w-32">
      <p className="font-mono text-[22px] font-semibold tabular-nums">{value}</p>
      <p className="mt-0.5 text-xs text-[oklch(0.82_0.03_265)]">{label}</p>
    </div>
  );
}
