-- Enforces "every ledger_transaction balances to zero in its base currency" independently of
-- the domain layer. Deferred to COMMIT so multiple ledger_entry inserts belonging to the same
-- transaction can happen in any order within one database transaction and only the final state
-- is checked.

CREATE FUNCTION check_ledger_transaction_balance() RETURNS TRIGGER AS $$
DECLARE
    affected_transaction_id UUID;
    net_balance BIGINT;
BEGIN
    IF TG_OP = 'DELETE' THEN
        affected_transaction_id := OLD.transaction_id;
    ELSE
        affected_transaction_id := NEW.transaction_id;
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
