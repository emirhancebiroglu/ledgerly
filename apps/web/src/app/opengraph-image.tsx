import { ImageResponse } from "next/og";

export const alt = "Ledgerly — AI-driven corporate expense ledger";
export const size = { width: 1200, height: 630 };
export const contentType = "image/png";

export default function Image() {
  return new ImageResponse(
    (
      <div
        style={{
          width: "100%",
          height: "100%",
          display: "flex",
          flexDirection: "column",
          alignItems: "flex-start",
          justifyContent: "center",
          padding: "80px",
          background: "#1a2b6b",
          color: "white",
          fontFamily: "sans-serif",
        }}
      >
        <div style={{ fontSize: 96, fontWeight: 700, display: "flex" }}>Ledgerly</div>
        <div style={{ fontSize: 36, marginTop: 24, color: "#c7d2fe", display: "flex", maxWidth: 900 }}>
          AI-driven corporate expense ledger
        </div>
      </div>
    ),
    { ...size },
  );
}
