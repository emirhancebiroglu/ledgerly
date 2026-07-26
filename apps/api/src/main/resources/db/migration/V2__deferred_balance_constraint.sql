-- Enforces "every ledger_transaction balances to zero in its base currency" independently of
-- the domain layer. Deferred to COMMIT so multiple ledger_entry inserts belonging to the same
-- transaction can happen in any order within one database transaction and only the final state
-- is checked.

CREATE FUNCTION check_ledger_transaction_balance() RETURNS TRIGGER AS $$
DECLARE
    affected_transaction_id UUID;
    expected_currency CHAR(3);
    mismatched_currency_count INT;
    entry_count INT;
    net_balance BIGINT;
BEGIN
    IF TG_TABLE_NAME = 'ledger_transaction' THEN
        affected_transaction_id := NEW.id;
    ELSIF TG_OP = 'DELETE' THEN
        affected_transaction_id := OLD.transaction_id;
    ELSE
        affected_transaction_id := NEW.transaction_id;
    END IF;

    SELECT base_currency INTO expected_currency
    FROM ledger_transaction
    WHERE id = affected_transaction_id;

    SELECT count(*) FILTER (WHERE base_currency <> expected_currency), count(*)
    INTO mismatched_currency_count, entry_count
    FROM ledger_entry
    WHERE transaction_id = affected_transaction_id;

    IF mismatched_currency_count > 0 THEN
        RAISE EXCEPTION 'ledger_transaction % has entries in a base currency other than %',
            affected_transaction_id, expected_currency;
    END IF;

    IF entry_count = 0 THEN
        RAISE EXCEPTION 'ledger_transaction % has no entries', affected_transaction_id;
    END IF;

    SELECT COALESCE(SUM(
        CASE WHEN direction = 'DEBIT' THEN base_amount_minor ELSE -base_amount_minor END
    ), 0)
    INTO net_balance
    FROM ledger_entry
    WHERE transaction_id = affected_transaction_id;

    IF net_balance <> 0 THEN
        RAISE EXCEPTION 'ledger_transaction % does not balance: net base amount %',
            affected_transaction_id, net_balance;
    END IF;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER ledger_entry_balance_check
    AFTER INSERT OR UPDATE OR DELETE ON ledger_entry
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION check_ledger_transaction_balance();

-- A transaction with zero entries never fires the trigger above (it is row-based on
-- ledger_entry), so a second trigger on ledger_transaction itself closes that gap.
CREATE CONSTRAINT TRIGGER ledger_transaction_has_entries_check
    AFTER INSERT ON ledger_transaction
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION check_ledger_transaction_balance();
