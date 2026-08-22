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
  /** Count badge — supplied by the caller, not baked into the config. */
  countKey?: "reviewQueue" | "unreadAlerts";
}

export interface DisabledNavItem {
  label: string;
  icon: LucideIcon;
}

/** Active routes available in the browser. M9.5 gave Alerts its own route and promoted it out of
 * `DISABLED_NAV_ITEMS`. */
export const NAV_ITEMS: NavItem[] = [
  { label: "Dashboard", href: "/dashboard", icon: LayoutGrid },
  { label: "Expenses", href: "/expenses", icon: Receipt },
  { label: "Upload", href: "/upload", icon: Upload },
  { label: "Review", href: "/review", icon: CheckCircle2, countKey: "reviewQueue" },
  { label: "Budgets", href: "/budgets", icon: Wallet },
  { label: "Alerts", href: "/alerts", icon: Bell, countKey: "unreadAlerts" },
];

/** Rendered as present-but-disabled so the shell stays honest about what is not built yet. */
export const DISABLED_NAV_ITEMS: DisabledNavItem[] = [
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
  { label: "Go to Alerts", href: "/alerts" },
];
