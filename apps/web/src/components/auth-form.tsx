"use client";

import { useActionState, useState } from "react";
import Link from "next/link";
import { AlertCircle, Eye, EyeOff, LockKeyhole, Mail } from "lucide-react";
import { login, register } from "@/app/actions/auth";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";

type AuthMode = "login" | "register";

interface AuthFormProps {
  mode: AuthMode;
  next?: string;
}

type FieldName = "fullName" | "company" | "email" | "password";
type FieldErrors = Partial<Record<FieldName, string>>;

export function AuthForm({ mode, next }: AuthFormProps) {
  const isRegister = mode === "register";
  const [state, action, pending] = useActionState(isRegister ? register : login, undefined);
  const [showPassword, setShowPassword] = useState(false);
  const [password, setPassword] = useState("");
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({});

  function validateField(name: FieldName, value: string): string | undefined {
    if (!value.trim()) return "This field is required.";
    if (name === "email" && !/^\S+@\S+\.\S+$/.test(value)) return "Enter a valid work email.";
    if (name === "password" && isRegister && value.length < 12) {
      return "Use at least 12 characters.";
    }
    return undefined;
  }

  function handleBlur(event: React.FocusEvent<HTMLInputElement>) {
    const name = event.currentTarget.name as FieldName;
    const error = validateField(name, event.currentTarget.value);
    setFieldErrors((current) => ({ ...current, [name]: error }));
  }

  const strength = passwordStrength(password);
  const submitLabel = pending
    ? isRegister
      ? "Creating workspace..."
      : "Signing in..."
    : isRegister
      ? "Create workspace"
      : "Sign in";

  return (
    <>
      <header>
        <h1 className="text-2xl font-semibold tracking-[-0.02em]">
          {isRegister ? "Create your workspace" : "Sign in to Ledgerly"}
        </h1>
        <p className="mt-1.5 text-[13.5px] text-muted-foreground text-pretty">
          {isRegister
            ? "Two minutes to set up. Connect documents whenever you are ready."
            : "Pick up where your ledger left off."}
        </p>
      </header>

      <div className="mt-[26px] flex flex-col gap-2" aria-label="Single sign-on options">
        <SsoButton provider="google" />
        <SsoButton provider="sso" />
      </div>

      <div className="my-5 flex items-center gap-3" aria-hidden="true">
        <span className="h-px flex-1 bg-[oklch(0.92_0.005_265)]" />
        <span className="text-[11.5px] text-[oklch(0.6_0.01_265)]">or</span>
        <span className="h-px flex-1 bg-[oklch(0.92_0.005_265)]" />
      </div>

      <form action={action} className="flex flex-col gap-3.5" noValidate>
        {next && <input type="hidden" name="next" value={next} />}
        {isRegister && (
          <div className="grid grid-cols-1 gap-2.5 min-[390px]:grid-cols-2">
            <AuthField
              error={fieldErrors.fullName}
              label="Full name"
              name="fullName"
              onBlur={handleBlur}
              placeholder="Elif Kaya"
              required
            />
            <AuthField
              error={fieldErrors.company}
              label="Company"
              name="company"
              onBlur={handleBlur}
              placeholder="Northwind Co."
              required
            />
          </div>
        )}
        <AuthField
          error={fieldErrors.email}
          icon={<Mail aria-hidden="true" className="size-[15px]" />}
          label="Work email"
          name="email"
          onBlur={handleBlur}
          placeholder="you@company.com"
          required
          type="email"
        />
        <div>
          <label className="mb-1.5 block text-[12.5px] font-medium" htmlFor="password">
            Password
          </label>
          <div
            className="flex h-[42px] items-center gap-2.5 rounded-lg border border-[oklch(0.9_0.006_265)] bg-card px-3 transition-[border-color,box-shadow] duration-150 focus-within:border-[oklch(0.6_0.13_265)] focus-within:shadow-[0_0_0_3px_oklch(0.5_0.16_265_/_0.12)] data-[invalid=true]:border-[oklch(0.6_0.16_25)] data-[invalid=true]:shadow-[0_0_0_3px_oklch(0.6_0.16_25_/_0.12)]"
            data-invalid={Boolean(fieldErrors.password)}
          >
            <LockKeyhole aria-hidden="true" className="size-[15px] shrink-0 text-[oklch(0.6_0.015_265)]" />
            <Input
              aria-describedby={fieldErrors.password ? "password-error" : undefined}
              aria-invalid={Boolean(fieldErrors.password)}
              className="h-full border-0 p-0 text-[13.5px] shadow-none focus-visible:border-0 focus-visible:ring-0 aria-invalid:border-0 aria-invalid:ring-0"
              id="password"
              minLength={isRegister ? 12 : undefined}
              name="password"
              onBlur={handleBlur}
              onChange={(event) => setPassword(event.target.value)}
              placeholder={isRegister ? "At least 12 characters" : "Enter your password"}
              required
              type={showPassword ? "text" : "password"}
              autoComplete={isRegister ? "new-password" : "current-password"}
            />
            <button
              aria-label={showPassword ? "Hide password" : "Show password"}
              className="flex shrink-0 cursor-pointer text-[oklch(0.6_0.015_265)] transition-colors hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50"
              onClick={() => setShowPassword((current) => !current)}
              type="button"
            >
              {showPassword ? <EyeOff className="size-[15px]" /> : <Eye className="size-[15px]" />}
            </button>
          </div>
          {fieldErrors.password && (
            <p id="password-error" className="mt-1.5 text-xs text-destructive">
              {fieldErrors.password}
            </p>
          )}
          {isRegister && <PasswordStrength strength={strength} />}
        </div>

        {!isRegister && (
          <label className="flex cursor-pointer items-center gap-2 text-[12.5px] text-[oklch(0.4_0.015_265)]">
            <input className="size-[15px] accent-primary" name="remember" type="checkbox" />
            Keep me signed in for 30 days
          </label>
        )}

        {state?.error && (
          <p
            role="alert"
            className="flex gap-2 rounded-lg bg-[oklch(0.96_0.03_25)] px-3 py-2.5 text-[12.5px] leading-relaxed text-[oklch(0.42_0.13_25)]"
          >
            <AlertCircle aria-hidden="true" className="mt-0.5 size-[15px] shrink-0" />
            <span>{state.error}</span>
          </p>
        )}

        <Button
          className="mt-0.5 h-[42px] w-full rounded-lg text-[13.5px] font-semibold transition-[transform,box-shadow,background] duration-150 hover:-translate-y-px hover:shadow-[0_4px_12px_oklch(0.2_0.02_265_/_0.14)] active:translate-y-0"
          disabled={pending}
          type="submit"
        >
          {pending && <span className="size-[15px] animate-spin rounded-full border-2 border-white/40 border-t-white" />}
          {submitLabel}
        </Button>
      </form>

      <p className="mt-[22px] text-center text-[13px] text-[oklch(0.5_0.01_265)]">
        {isRegister ? "Already have a workspace?" : "New to Ledgerly?"}{" "}
        <Link className="font-semibold text-primary hover:underline" href={isRegister ? "/login" : "/register"}>
          {isRegister ? "Sign in" : "Create an account"}
        </Link>
      </p>
    </>
  );
}

function AuthField({
  error,
  icon,
  label,
  name,
  onBlur,
  placeholder,
  required,
  type = "text",
}: {
  error?: string;
  icon?: React.ReactNode;
  label: string;
  name: FieldName;
  onBlur: (event: React.FocusEvent<HTMLInputElement>) => void;
  placeholder: string;
  required: boolean;
  type?: "email" | "text";
}) {
  const errorId = `${name}-error`;
  return (
    <div>
      <label className="mb-1.5 block text-[12.5px] font-medium" htmlFor={name}>
        {label}
      </label>
      <div
        className="flex h-[42px] items-center gap-2.5 rounded-lg border border-[oklch(0.9_0.006_265)] bg-card px-3 transition-[border-color,box-shadow] duration-150 focus-within:border-[oklch(0.6_0.13_265)] focus-within:shadow-[0_0_0_3px_oklch(0.5_0.16_265_/_0.12)] data-[invalid=true]:border-[oklch(0.6_0.16_25)] data-[invalid=true]:shadow-[0_0_0_3px_oklch(0.6_0.16_25_/_0.12)]"
        data-invalid={Boolean(error)}
      >
        {icon && <span className="shrink-0 text-[oklch(0.6_0.015_265)]">{icon}</span>}
        <Input
          aria-describedby={error ? errorId : undefined}
          aria-invalid={Boolean(error)}
          autoComplete={type === "email" ? "email" : "name"}
          className="h-full border-0 p-0 text-[13.5px] shadow-none focus-visible:border-0 focus-visible:ring-0 aria-invalid:border-0 aria-invalid:ring-0"
          id={name}
          name={name}
          onBlur={onBlur}
          placeholder={placeholder}
          required={required}
          type={type}
        />
      </div>
      {error && (
        <p id={errorId} className="mt-1.5 text-xs text-destructive">
          {error}
        </p>
      )}
    </div>
  );
}

function SsoButton({ provider }: { provider: "google" | "sso" }) {
  const isGoogle = provider === "google";
  return (
    <button
      aria-label={isGoogle ? "Continue with Google, coming soon" : "Continue with SSO, coming soon"}
      className="flex h-[42px] w-full cursor-not-allowed items-center justify-center gap-2 rounded-lg border border-[oklch(0.9_0.006_265)] bg-card text-[13.5px] font-medium opacity-55"
      disabled
      type="button"
    >
      {isGoogle ? <GoogleMark /> : <Mail aria-hidden="true" className="size-4 stroke-[1.9]" />}
      Continue with {isGoogle ? "Google" : "SSO"} · Coming soon
    </button>
  );
}

function GoogleMark() {
  return (
    <svg aria-hidden="true" className="size-4" viewBox="0 0 18 18">
      <path fill="#4285F4" d="M17.6 9.2c0-.6-.05-1.2-.16-1.8H9v3.4h4.8a4.1 4.1 0 0 1-1.8 2.7v2.2h2.9c1.7-1.6 2.7-3.9 2.7-6.5z" />
      <path fill="#34A853" d="M9 18c2.4 0 4.5-.8 6-2.2l-2.9-2.2c-.8.5-1.8.9-3.1.9-2.4 0-4.4-1.6-5.1-3.8H.9v2.3A9 9 0 0 0 9 18z" />
      <path fill="#FBBC05" d="M3.9 10.7a5.4 5.4 0 0 1 0-3.4V5H.9a9 9 0 0 0 0 8l3-2.3z" />
      <path fill="#EA4335" d="M9 3.6c1.3 0 2.5.5 3.4 1.3l2.6-2.6A9 9 0 0 0 .9 5l3 2.3C4.6 5.2 6.6 3.6 9 3.6z" />
    </svg>
  );
}

function PasswordStrength({ strength }: { strength: number }) {
  const labels = ["Too weak — add length and a number", "Weak — try a longer passphrase", "Good — one more word makes it strong", "Strong password"];
  const colors = ["bg-destructive", "bg-warning", "bg-[oklch(0.7_0.13_110)]", "bg-success"];
  return (
    <div className="mt-2.5">
      <div className="flex gap-1" aria-label={`Strength: ${labels[strength - 1]}`} role="status">
        {[0, 1, 2, 3].map((index) => (
          <span
            key={index}
            className={`h-[3px] flex-1 rounded-sm transition-colors duration-200 ${index < strength ? colors[strength - 1] : "bg-[oklch(0.92_0.005_265)]"}`}
          />
        ))}
      </div>
      <p className="mt-1.5 text-[11.5px] text-muted-foreground">{labels[strength - 1]}</p>
    </div>
  );
}

function passwordStrength(password: string): number {
  if (password.length < 12) return 1;
  const characterGroups = [/[a-z]/, /[A-Z]/, /\d/, /[^A-Za-z\d]/].filter((pattern) => pattern.test(password)).length;
  if (password.length >= 20 && characterGroups >= 3) return 4;
  if (characterGroups >= 3) return 3;
  return 2;
}
