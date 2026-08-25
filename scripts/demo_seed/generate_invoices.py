"""Generates the demo organization's ~3-month invoice corpus.

Separate from apps/ai/evals/fixtures/synthetic/generate.py (accuracy-eval edge cases) and from
generate_policies.py (policy documents) — this produces the invoices T5.4 feeds through the
real `ai` extraction/categorization pipeline to build the demo org's expense history. Run once
(``python generate_invoices.py`` from this directory) whenever the corpus needs regenerating;
the PDFs and manifest are committed alongside this script.

Scenario design (see docs/milestones.md T5.2):
  - CloudHost Inc (Software & Subscriptions): 3 monthly hosting invoices, plain postings.
  - Skyline Airlines (Travel & Transport): 2 flights, one with a deliberately sparse/ambiguous
    layout meant to produce a low-confidence categorization.
  - Boardroom Bistro (Meals & Entertainment): 4 client meals within one month, sized to cross
    the demo budget's threshold for that category/period.
  - QuickPrint Supplies (Office & Supplies): 3 normal orders, plus one deliberate re-upload of
    an earlier invoice_number/vendor/amount to produce a CONFIRMED duplicate match.
  - TechGear Wholesale (Equipment & Hardware): 4 modest purchases plus one outlier far above
    the rest, sized to cross the anomaly detector's high z-score threshold.
  - CoWork Space (Office & Supplies): 3 monthly rent invoices, plain postings.
"""

import json
from datetime import date
from pathlib import Path

from reportlab.lib.pagesizes import A4
from reportlab.pdfgen import canvas

OUT_DIR = Path(__file__).parent / "pdfs" / "invoices"
OUT_DIR.mkdir(parents=True, exist_ok=True)
MANIFEST_PATH = Path(__file__).parent / "invoice_manifest.json"


def write_invoice(filename: str, lines: list[str]) -> None:
    path = OUT_DIR / filename
    c = canvas.Canvas(str(path), pagesize=A4)
    c.setFont("Helvetica", 11)
    y = 800
    for line in lines:
        c.drawString(50, y, line)
        y -= 18
    c.save()


manifest: list[dict] = []


def invoice(
    filename: str,
    lines: list[str],
    *,
    vendor: str,
    invoice_number: str,
    currency: str,
    total_minor: int,
    tax_minor: int,
    document_date: str,
    category: str,
    scenario: str,
) -> None:
    write_invoice(filename, lines)
    manifest.append(
        {
            "path": f"pdfs/invoices/{filename}",
            "expected": {
                "vendor": vendor,
                "invoice_number": invoice_number,
                "currency": currency,
                "total_minor": total_minor,
                "tax_minor": tax_minor,
                "document_date": document_date,
            },
            "category": category,
            "scenario": scenario,
        }
    )


# --- CloudHost Inc: 3 monthly hosting invoices, plain postings ---
for i, (month, inv_no, amount) in enumerate(
    [("2026-03", "CH-2026-0301", 12900), ("2026-04", "CH-2026-0401", 12900), ("2026-05", "CH-2026-0501", 14900)]
):
    invoice(
        f"cloudhost_{month}.pdf",
        [
            "CLOUDHOST INC",
            "500 Server Row, Austin, TX",
            "INVOICE",
            f"Invoice No: {inv_no}",
            f"Invoice Date: {month}-01",
            "",
            "Description                    Amount",
            "Cloud hosting — Standard tier   " + f"{amount / 100:.2f}",
            "",
            f"Subtotal:                       {amount / 100:.2f}",
            "Tax (0%):                          0.00",
            f"Total:                          {amount / 100:.2f}",
            "Currency: USD",
        ],
        vendor="CloudHost Inc",
        invoice_number=inv_no,
        currency="USD",
        total_minor=amount,
        tax_minor=0,
        document_date=f"{month}-01",
        category="Software & Subscriptions",
        scenario="normal",
    )

# --- Skyline Airlines: 2 flights, one deliberately sparse for low-confidence categorization ---
invoice(
    "skyline_flight_march.pdf",
    [
        "SKYLINE AIRLINES",
        "INVOICE",
        "Invoice No: SKY-88213",
        "Invoice Date: 2026-03-10",
        "",
        "Route: AUS -> SFO -> AUS",
        "Passenger: J. Doe",
        "Fare class: Economy",
        "",
        "Description                    Amount",
        "Fare                             340.00",
        "",
        "Subtotal:                        340.00",
        "Taxes and fees:                   42.00",
        "Total:                           382.00",
        "Currency: USD",
    ],
    vendor="Skyline Airlines",
    invoice_number="SKY-88213",
    currency="USD",
    total_minor=38200,
    tax_minor=4200,
    document_date="2026-03-10",
    category="Travel & Transport",
    scenario="normal",
)
invoice(
    "skyline_flight_april_sparse.pdf",
    [
        "GENERAL MERCHANDISE RECEIPT",
        "",
        "Thank you for your purchase.",
        "",
        "Date: 2026-04-18",
        "Item: Miscellaneous goods and services",
        "TOTAL: USD 415.50",
        "(tax included)",
    ],
    vendor="Skyline Airlines",
    invoice_number="",
    currency="USD",
    total_minor=41550,
    tax_minor=0,
    document_date="2026-04-18",
    category="Travel & Transport",
    scenario="low_confidence",
)

# --- Boardroom Bistro: 4 client meals within one month, crosses the budget threshold ---
for i, (day, inv_no, amount) in enumerate(
    [("05", "BB-2026-0605", 18500), ("12", "BB-2026-0612", 21000), ("19", "BB-2026-0619", 16500), ("26", "BB-2026-0626", 19800)]
):
    invoice(
        f"boardroom_bistro_2026-06-{day}.pdf",
        [
            "BOARDROOM BISTRO",
            "77 Market Square, Austin, TX",
            "INVOICE",
            f"Invoice No: {inv_no}",
            f"Invoice Date: 2026-06-{day}",
            "",
            "Description                    Amount",
            "Client dinner — party of 4      " + f"{amount / 100:.2f}",
            "",
            f"Subtotal:                       {amount / 100:.2f}",
            "Tax:                                0.00",
            f"Total:                          {amount / 100:.2f}",
            "Currency: USD",
        ],
        vendor="Boardroom Bistro",
        invoice_number=inv_no,
        currency="USD",
        total_minor=amount,
        tax_minor=0,
        document_date=f"2026-06-{day}",
        category="Meals & Entertainment",
        scenario="budget_threshold",
    )

# --- QuickPrint Supplies: 3 normal orders + 1 duplicate re-upload of the first ---
for month, inv_no, amount in [
    ("2026-03", "QP-2026-1001", 8900),
    ("2026-04", "QP-2026-1102", 6400),
    ("2026-05", "QP-2026-1203", 9200),
]:
    invoice(
        f"quickprint_{month}.pdf",
        [
            "QUICKPRINT SUPPLIES",
            "12 Industrial Way, Austin, TX",
            "INVOICE",
            f"Invoice No: {inv_no}",
            f"Invoice Date: {month}-15",
            "",
            "Description                    Amount",
            "Office paper & toner            " + f"{amount / 100:.2f}",
            "",
            f"Subtotal:                       {amount / 100:.2f}",
            "Tax:                                0.00",
            f"Total:                          {amount / 100:.2f}",
            "Currency: USD",
        ],
        vendor="QuickPrint Supplies",
        invoice_number=inv_no,
        currency="USD",
        total_minor=amount,
        tax_minor=0,
        document_date=f"{month}-15",
        category="Office & Supplies",
        scenario="normal",
    )
# Deliberate duplicate: same vendor + same invoice_number as the first QuickPrint invoice above.
invoice(
    "quickprint_2026-03_duplicate.pdf",
    [
        "QUICKPRINT SUPPLIES",
        "12 Industrial Way, Austin, TX",
        "INVOICE",
        "Invoice No: QP-2026-1001",
        "Invoice Date: 2026-03-15",
        "",
        "Description                    Amount",
        "Office paper & toner              89.00",
        "",
        "Subtotal:                         89.00",
        "Tax:                                0.00",
        "Total:                            89.00",
        "Currency: USD",
    ],
    vendor="QuickPrint Supplies",
    invoice_number="QP-2026-1001",
    currency="USD",
    total_minor=8900,
    tax_minor=0,
    document_date="2026-03-15",
    category="Office & Supplies",
    scenario="duplicate_confirmed",
)

# --- TechGear Wholesale: 4 modest purchases + 1 outlier far above the rest ---
for month, inv_no, amount in [
    ("2026-03", "TG-2026-4401", 4500),
    ("2026-04", "TG-2026-4502", 5200),
    ("2026-05", "TG-2026-4603", 4800),
    ("2026-06", "TG-2026-4704", 5100),
]:
    invoice(
        f"techgear_{month}.pdf",
        [
            "TECHGEAR WHOLESALE",
            "900 Warehouse Blvd, Austin, TX",
            "INVOICE",
            f"Invoice No: {inv_no}",
            f"Invoice Date: {month}-20",
            "",
            "Description                    Amount",
            "Assorted peripherals            " + f"{amount / 100:.2f}",
            "",
            f"Subtotal:                       {amount / 100:.2f}",
            "Tax:                                0.00",
            f"Total:                          {amount / 100:.2f}",
            "Currency: USD",
        ],
        vendor="TechGear Wholesale",
        invoice_number=inv_no,
        currency="USD",
        total_minor=amount,
        tax_minor=0,
        document_date=f"{month}-20",
        category="Equipment & Hardware",
        scenario="normal",
    )
invoice(
    "techgear_2026-06_outlier.pdf",
    [
        "TECHGEAR WHOLESALE",
        "900 Warehouse Blvd, Austin, TX",
        "INVOICE",
        "Invoice No: TG-2026-4899",
        "Invoice Date: 2026-06-25",
        "",
        "Description                    Amount",
        "Server rack + networking gear    4800.00",
        "",
        "Subtotal:                      4800.00",
        "Tax:                               0.00",
        "Total:                         4800.00",
        "Currency: USD",
    ],
    vendor="TechGear Wholesale",
    invoice_number="TG-2026-4899",
    currency="USD",
    total_minor=480000,
    tax_minor=0,
    document_date="2026-06-25",
    category="Equipment & Hardware",
    scenario="anomaly_high",
)

# --- CoWork Space: 3 monthly rent invoices, plain postings ---
for month, inv_no, amount in [
    ("2026-03", "CW-2026-0301", 95000),
    ("2026-04", "CW-2026-0401", 95000),
    ("2026-05", "CW-2026-0501", 95000),
]:
    invoice(
        f"cowork_{month}.pdf",
        [
            "COWORK SPACE",
            "200 Startup Alley, Austin, TX",
            "INVOICE",
            f"Invoice No: {inv_no}",
            f"Invoice Date: {month}-01",
            "",
            "Description                    Amount",
            "Monthly desk rental             " + f"{amount / 100:.2f}",
            "",
            f"Subtotal:                       {amount / 100:.2f}",
            "Tax:                                0.00",
            f"Total:                          {amount / 100:.2f}",
            "Currency: USD",
        ],
        vendor="CoWork Space",
        invoice_number=inv_no,
        currency="USD",
        total_minor=amount,
        tax_minor=0,
        document_date=f"{month}-01",
        category="Office & Supplies",
        scenario="normal",
    )

MANIFEST_PATH.write_text(json.dumps({"version": 1, "entries": manifest}, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

print(f"Wrote {len(manifest)} invoice PDFs to {OUT_DIR}")
print(f"Wrote manifest to {MANIFEST_PATH}")
