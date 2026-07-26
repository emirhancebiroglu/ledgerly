-- Seeds a demo organization with one account per chart-of-accounts type, so a fresh database
-- has a usable starting ledger. Idempotent: safe to run this migration's logic again (it won't
-- be, by Flyway's versioned-once model, but the ON CONFLICT guards keep it safe under a manual
-- re-apply or a copy-paste into a repeatable migration later).

INSERT INTO organization (id, name, base_currency)
VALUES ('00000000-0000-0000-0000-000000000001', 'Demo Organization', 'EUR')
ON CONFLICT (id) DO NOTHING;

INSERT INTO account (organization_id, name, account_type, currency)
VALUES
    ('00000000-0000-0000-0000-000000000001', 'Cash', 'ASSET', 'EUR'),
    ('00000000-0000-0000-0000-000000000001', 'Accounts Payable', 'LIABILITY', 'EUR'),
    ('00000000-0000-0000-0000-000000000001', 'General Expense', 'EXPENSE', 'EUR'),
    ('00000000-0000-0000-0000-000000000001', 'Service Revenue', 'REVENUE', 'EUR'),
    ('00000000-0000-0000-0000-000000000001', 'Owner Equity', 'EQUITY', 'EUR')
ON CONFLICT (organization_id, name) DO NOTHING;
