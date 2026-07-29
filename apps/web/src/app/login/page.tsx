import { LoginForm } from "@/components/login-form";
import { AuthShell } from "@/components/auth-shell";

interface LoginPageProps {
  searchParams: Promise<{ next?: string }>;
}

export default async function LoginPage({ searchParams }: LoginPageProps) {
  const { next } = await searchParams;

  return (
    <AuthShell>
      <LoginForm next={next} />
    </AuthShell>
  );
}
