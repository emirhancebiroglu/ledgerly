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

/** Active routes available in the browser. Alerts remains intentionally deferred: M8 exposes
 * recent alerts on the dashboard, but does not add an alert-management screen. */
export const NAV_ITEMS: NavItem[] = [
  { label: "Dashboard", href: "/dashboard", icon: LayoutGrid },
  { label: "Expenses", href: "/expenses", icon: Receipt },
  { label: "Upload", href: "/upload", icon: Upload },
  { label: "Review", href: "/review", icon: CheckCircle2, countKey: "reviewQueue" },
  { label: "Budgets", href: "/budgets", icon: Wallet },
];

/** Alert records are visible on Dashboard in M8. A dedicated management route is deferred. */
export const DISABLED_NAV_ITEMS: DisabledNavItem[] = [
  { label: "Alerts", icon: Bell },
  { label: "Policies", icon: FileText },
];

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
];
