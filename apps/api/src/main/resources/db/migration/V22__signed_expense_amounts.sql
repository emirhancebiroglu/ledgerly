-- Credit notes are negative business expenses, while individual ledger entries retain a positive
-- magnitude and express sign through their debit/credit direction.
ALTER TABLE expense DROP CONSTRAINT expense_amount_minor_check;
ALTER TABLE expense
    ADD CONSTRAINT expense_amount_minor_check CHECK (amount_minor <> 0);
