"use client";

import { useRef, useState } from "react";
import { useRouter, useSearchParams, usePathname } from "next/navigation";
import { Search } from "lucide-react";

const SEARCH_DEBOUNCE_MS = 300;

function pushParams(
  router: ReturnType<typeof useRouter>,
  pathname: string,
  current: URLSearchParams,
  updates: Record<string, string | null>,
) {
  const next = new URLSearchParams(current);
  for (const [key, value] of Object.entries(updates)) {
    if (value === null || value === "") {
      next.delete(key);
    } else {
      next.set(key, value);
    }
  }
  const queryString = next.toString();
  router.push(queryString ? `${pathname}?${queryString}` : pathname);
}

interface SearchInputProps {
  initialValue: string;
  onDebouncedChange: (value: string) => void;
}

/** Keyed by the URL's own search value in the parent, so it remounts (picking up the new
 * `initialValue`) whenever that value changes externally — e.g. the browser back button, or a
 * filter reset — without syncing external state into local state via an effect. */
function SearchInput({ initialValue, onDebouncedChange }: SearchInputProps) {
  const [value, setValue] = useState(initialValue);
  const debounceRef = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

  function onChange(next: string) {
    setValue(next);
    if (debounceRef.current) {
      clearTimeout(debounceRef.current);
    }
    debounceRef.current = setTimeout(() => onDebouncedChange(next), SEARCH_DEBOUNCE_MS);
  }

  return (
    <div className="flex flex-1 items-center gap-2 rounded-lg border border-input bg-background px-3 py-2">
      <Search className="size-[15px] shrink-0 text-muted-foreground" aria-hidden />
      <input
        type="search"
        value={value}
        onChange={(event) => onChange(event.target.value)}
        placeholder="Search vendor, category, amount..."
        aria-label="Search expenses"
        className="w-full border-none bg-transparent text-[13px] outline-none placeholder:text-muted-foreground"
      />
    </div>
  );
}

export function ExpensesFilters() {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const currentSearch = searchParams.get("search") ?? "";

  return (
    <div className="flex flex-col gap-2.5 sm:flex-row">
      <SearchInput
        key={currentSearch}
        initialValue={currentSearch}
        onDebouncedChange={(value) =>
          pushParams(router, pathname, searchParams, { search: value || null })
        }
      />

      <label className="sr-only" htmlFor="status-filter">
        Filter by status
      </label>
      <select
        id="status-filter"
        value={searchParams.get("status") ?? ""}
        onChange={(event) =>
          pushParams(router, pathname, searchParams, { status: event.target.value || null })
        }
        className="rounded-lg border border-input bg-background px-3.5 py-2 text-[12.5px] text-muted-foreground"
      >
        <option value="">All statuses</option>
        <option value="POSTED">Posted</option>
        <option value="NEEDS_REVIEW">Needs review</option>
      </select>

      <label className="sr-only" htmlFor="sort-filter">
        Sort by
      </label>
      <select
        id="sort-filter"
        value={searchParams.get("sort") ?? "date,desc"}
        onChange={(event) =>
          pushParams(router, pathname, searchParams, { sort: event.target.value })
        }
        className="rounded-lg border border-input bg-background px-3.5 py-2 text-[12.5px] text-muted-foreground"
      >
        <option value="date,desc">Newest first</option>
        <option value="date,asc">Oldest first</option>
        <option value="amount,desc">Amount: high to low</option>
        <option value="amount,asc">Amount: low to high</option>
      </select>
    </div>
  );
}
