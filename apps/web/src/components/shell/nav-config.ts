import {
  LayoutGrid,
  Receipt,
  Upload,
  CheckCircle2,
  Wallet,
  Bell,
  FileText,
  type LucideIcon,
} from "lucide-react";

export interface NavItem {
  label: string;
  href: string;
  icon: LucideIcon;
  /** Review queue's count badge — supplied by the caller, not baked into the config. */
  countKey?: "reviewQueue";
}

export interface DisabledNavItem {
  label: string;
  icon: LucideIcon;
}

/** Active routes available in the browser. Alerts remains intentionally deferred — see
 * `DISABLED_NAV_ITEMS`. */
export const NAV_ITEMS: NavItem[] = [
  { label: "Dashboard", href: "/dashboard", icon: LayoutGrid },
  { label: "Expenses", href: "/expenses", icon: Receipt },
  { label: "Upload", href: "/upload", icon: Upload },
  { label: "Review", href: "/review", icon: CheckCircle2, countKey: "reviewQueue" },
  { label: "Budgets", href: "/budgets", icon: Wallet },
  { label: "Policies", href: "/policies", icon: FileText },
];

/** Rendered as present-but-disabled so the shell stays honest about what is not built yet.
 * Alerts owns its own route when it ships; M9.4 removed the dashboard's alerts card rather than
 * leaving alert records on a screen that is not about them, so nothing surfaces them today. */
export const DISABLED_NAV_ITEMS: DisabledNavItem[] = [{ label: "Alerts", icon: Bell }];

export interface QuickJumpItem {
  label: string;
  href: string;
}

export const QUICK_JUMP_ITEMS: QuickJumpItem[] = [
  { label: "Go to Dashboard", href: "/dashboard" },
  { label: "Upload a document", href: "/upload" },
  { label: "Go to Review queue", href: "/review" },
  { label: "Go to Expenses", href: "/expenses" },
  { label: "Go to Budgets", href: "/budgets" },
  { label: "Go to Policies", href: "/policies" },
];
