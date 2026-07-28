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

/** Active routes per the M7 handoff's sidebar — Budgets excluded (deferred to M8; see
 * projects/ledgerly/todo.md's M7b planning decision). */
export const NAV_ITEMS: NavItem[] = [
  { label: "Dashboard", href: "/dashboard", icon: LayoutGrid },
  { label: "Expenses", href: "/expenses", icon: Receipt },
  { label: "Upload", href: "/upload", icon: Upload },
  { label: "Review", href: "/review", icon: CheckCircle2, countKey: "reviewQueue" },
];

/** Budgets joins Alerts/Policies here rather than as a live nav item — the `budget` table and
 * its API don't exist until M8, so there is nothing for a Budgets route to render against. */
export const DISABLED_NAV_ITEMS: DisabledNavItem[] = [
  { label: "Budgets", icon: Wallet },
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
];
