-- ledger_entry rows are append-only: corrections are new reversing entries, never a mutation
-- of an existing row. A dedicated application role has INSERT/SELECT everywhere but no
-- UPDATE/DELETE on ledger_entry, so the restriction holds even if a future code path forgets it.
--
-- This role exists to prove the grant boundary in LedgerEntryImmutabilityIT; it is not a
-- production credential. Real connection-level auth is decided at M3. The password here is a
-- fixed, non-secret placeholder — every environment (local, CI, Testcontainers) creates it
-- identically and nothing outside this migration and its test ever reads it from config.

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ledgerly_app') THEN
        CREATE ROLE ledgerly_app LOGIN PASSWORD 'ledgerly_app_role_fixture';
    END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO ledgerly_app;

GRANT SELECT, INSERT, UPDATE, DELETE ON
    organization, app_user, account, category, fx_rate, ledger_transaction
    TO ledgerly_app;

GRANT SELECT, INSERT ON ledger_entry TO ledgerly_app;
REVOKE UPDATE, DELETE ON ledger_entry FROM ledgerly_app;

GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO ledgerly_app;
