import { AuthForm } from "@/components/auth-form";

export function LoginForm({ next }: { next?: string }) {
  return <AuthForm mode="login" next={next} />;
}
