"""Generates the 3 synthetic edge-case PDFs this fixture group needs.

Run once (``python generate.py`` from this directory) whenever a synthetic PDF needs
regenerating. The PDFs themselves are committed alongside the fixtures — deterministic
generation from this script, not a build step the eval harness depends on.
"""

from pathlib import Path

from reportlab.lib.pagesizes import A4
from reportlab.pdfgen import canvas

OUT_DIR = Path(__file__).parent


def write_invoice(filename: str, lines: list[str]) -> None:
    path = OUT_DIR / filename
    c = canvas.Canvas(str(path), pagesize=A4)
    c.setFont("Helvetica", 11)
    y = 800
    for line in lines:
        c.drawString(50, y, line)
        y -= 18
    c.save()


write_invoice(
    "01_refund_negative_total.pdf",
    [
        "GLOBEX RETAIL LTD",
        "123 Market Street, London, UK",
        "CREDIT NOTE",
        "Credit Note No: CN-2026-00931",
        "Issue Date: 2026-03-14",
        "",
        "Description                Qty      Amount",
        "Refund: Wireless Mouse      1       -19.99",
        "Refund: USB-C Cable         1        -8.50",
        "",
        "Subtotal:                          -28.49",
        "VAT (0% on refund):                  0.00",
        "Total Credit:                      -28.49",
        "Currency: GBP",
    ],
)

write_invoice(
    "02_no_itemisation.pdf",
    [
        "QUICKSTOP CONVENIENCE STORE",
        "45 High Street, Dublin, Ireland",
        "RECEIPT",
        "Receipt No: R-88213",
        "Date: 2026-01-09",
        "",
        "Thank you for your purchase.",
        "",
        "TOTAL: EUR 6.40",
        "VAT included: EUR 0.53",
        "Payment: Cash",
    ],
)

write_invoice(
    "03_zero_tax_exempt.pdf",
    [
        "SUNRISE MEDICAL SUPPLIES INC",
        "900 Health Ave, Toronto, Canada",
        "INVOICE",
        "Invoice No: INV-2026-4471",
        "Invoice Date: 2026-05-02",
        "Tax-exempt sale (medical supplies)",
        "",
        "Description                 Qty     Amount",
        "First Aid Kit, Large          2      84.00",
        "Sterile Gauze Pack (10x)      3      27.00",
        "",
        "Subtotal:                          111.00",
        "Tax (exempt):                         0.00",
        "Total:                             111.00",
        "Currency: CAD",
    ],
)

print("Wrote 3 synthetic fixture PDFs to", OUT_DIR)
