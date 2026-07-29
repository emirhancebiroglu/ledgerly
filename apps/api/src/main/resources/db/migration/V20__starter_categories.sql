-- Every organization needs an explicit, editable category taxonomy before the LLM can classify an
-- expense. Existing organizations that already configured any category remain untouched.

INSERT INTO category (organization_id, name)
SELECT organization.id, starter_category.name
FROM organization
CROSS JOIN (
    VALUES
        ('Software & Subscriptions'),
        ('Travel & Transport'),
        ('Meals & Entertainment'),
        ('Office & Supplies'),
        ('Professional Services'),
        ('Marketing & Advertising'),
        ('Utilities'),
        ('Equipment & Hardware'),
        ('Taxes & Fees'),
        ('Insurance'),
        ('Training & Education'),
        ('Other Operating Expenses')
) AS starter_category(name)
WHERE NOT EXISTS (
    SELECT 1
    FROM category
    WHERE category.organization_id = organization.id
);
