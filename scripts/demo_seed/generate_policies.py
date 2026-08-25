"""Generates the demo organization's expense-policy PDFs.

Separate from apps/ai/evals/fixtures/synthetic/generate.py: that corpus exists to measure
extraction accuracy against edge cases, this one exists to give the demo org's categorization
citations something real-sounding to point at. Run once (``python generate_policies.py`` from
this directory) whenever a policy PDF needs regenerating — the PDFs are committed alongside
this script, not built on demand.
"""

from pathlib import Path

from reportlab.lib.pagesizes import A4
from reportlab.pdfgen import canvas

OUT_DIR = Path(__file__).parent / "pdfs" / "policies"
OUT_DIR.mkdir(parents=True, exist_ok=True)


def write_policy(filename: str, title: str, lines: list[str]) -> None:
    path = OUT_DIR / filename
    c = canvas.Canvas(str(path), pagesize=A4)
    c.setFont("Helvetica-Bold", 14)
    c.drawString(50, 800, title)
    c.setFont("Helvetica", 10)
    y = 770
    for line in lines:
        if y < 50:
            c.showPage()
            c.setFont("Helvetica", 10)
            y = 800
        c.drawString(50, y, line)
        y -= 16
    c.save()


write_policy(
    "travel_and_expense_policy.pdf",
    "Travel & Expense Policy",
    [
        "Effective 2026-01-01. Applies to all employees submitting travel-related expenses.",
        "",
        "1. Ground transportation (taxis, rideshare, public transit) is reimbursable in full",
        "   for business travel. Category: Travel & Transport.",
        "2. Flights and rail must be booked at economy/standard class for trips under 6 hours;",
        "   business class requires manager pre-approval. Category: Travel & Transport.",
        "3. Hotel stays are reimbursable up to the local per-diem rate published by Finance.",
        "   Category: Travel & Transport.",
        "4. Meals while traveling on company business are reimbursable up to $75/day without",
        "   itemized receipts for amounts under $25. Category: Meals & Entertainment.",
        "5. Personal entertainment, mini-bar charges, and in-flight upgrades are not",
        "   reimbursable under any circumstance.",
        "6. Fuel and parking for a personal vehicle used on company business is reimbursable",
        "   at the standard mileage rate. Category: Travel & Transport.",
    ],
)

write_policy(
    "software_and_subscriptions_policy.pdf",
    "Software & Subscriptions Policy",
    [
        "Effective 2026-01-01. Applies to all software, SaaS, and digital-service purchases.",
        "",
        "1. Individual SaaS subscriptions under $50/month may be expensed directly by the",
        "   employee without prior approval. Category: Software & Subscriptions.",
        "2. Any subscription or license at or above $50/month requires IT approval before",
        "   purchase, on the grounds of data-security review and license-count tracking.",
        "   Category: Software & Subscriptions.",
        "3. Cloud infrastructure and hosting costs (compute, storage, bandwidth) are always",
        "   Category: Software & Subscriptions, regardless of amount.",
        "4. One-time software purchases (perpetual licenses, plugins, developer tools) follow",
        "   the same $50 approval threshold as recurring subscriptions.",
        "5. Domain registration and DNS services are Category: Software & Subscriptions.",
        "6. Hardware purchased to run software (laptops, monitors, peripherals) is never this",
        "   category — see the Equipment & Hardware category instead.",
    ],
)

write_policy(
    "client_entertainment_policy.pdf",
    "Client Entertainment Policy",
    [
        "Effective 2026-01-01. Applies to meals, events, and gifts involving external clients.",
        "",
        "1. Client meals are reimbursable up to $150 per person when a business purpose and",
        "   attendee list are recorded. Category: Meals & Entertainment.",
        "2. Event tickets or venue costs for client entertainment require manager approval",
        "   above $300 total. Category: Meals & Entertainment.",
        "3. Client gifts are reimbursable up to $75 per recipient per calendar year, and must",
        "   comply with the recipient organization's own gift-acceptance policy where known.",
        "   Category: Meals & Entertainment.",
        "4. Alcohol at a client meal is reimbursable only when it does not exceed 25% of the",
        "   total bill.",
        "5. Internal team meals and celebrations (no external client present) are never this",
        "   category — see Office & Supplies or Training & Education instead, depending on",
        "   the occasion.",
    ],
)

print(f"Wrote 3 policy PDFs to {OUT_DIR}")
