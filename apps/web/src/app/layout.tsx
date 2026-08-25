import type { Metadata } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import "./globals.css";

const geistSans = Geist({
  variable: "--font-geist-sans",
  subsets: ["latin"],
});

const geistMono = Geist_Mono({
  variable: "--font-geist-mono",
  subsets: ["latin"],
});

export const metadata: Metadata = {
  // Resolves the og:image/twitter:image URLs opengraph-image.tsx generates into absolute
  // ones — without this Next.js falls back to localhost, which is wrong for a real deploy.
  // Set NEXT_PUBLIC_SITE_URL to the real Vercel/custom domain once one exists (M10).
  metadataBase: new URL(process.env.NEXT_PUBLIC_SITE_URL ?? "http://localhost:3000"),
  title: "Ledgerly",
  description:
    "AI-driven corporate expense ledger. Upload an invoice, an agent extracts and categorizes " +
    "it, the system posts it to a double-entry ledger, and a budget guard flags anomalies " +
    "before they become surprises.",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html
      lang="en"
      className={`${geistSans.variable} ${geistMono.variable} h-full antialiased`}
    >
      <body className="min-h-full flex flex-col">{children}</body>
    </html>
  );
}
