import { ImageResponse } from "next/og";

export const size = { width: 32, height: 32 };
export const contentType = "image/png";

// Same brand color/mark as opengraph-image.tsx — "L" for Ledgerly, not a generic default icon.
export default function Icon() {
  return new ImageResponse(
    (
      <div
        style={{
          width: "100%",
          height: "100%",
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          background: "#1a2b6b",
          color: "white",
          fontFamily: "sans-serif",
          fontSize: 22,
          fontWeight: 700,
        }}
      >
        L
      </div>
    ),
    { ...size },
  );
}
