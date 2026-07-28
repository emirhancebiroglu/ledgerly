import { useEffect, useRef, useState } from "react";
import type { DocumentStatus } from "@/lib/document-upload";

export type ConnectionState = "connecting" | "open" | "stalled";

export interface DocumentStatusState {
  status: DocumentStatus | null;
  failureReason: string | null;
  connection: ConnectionState;
}

interface StatusEventPayload {
  documentId: string;
  organizationId: string;
  status: DocumentStatus;
  detail: string | null;
}

const RECONNECT_DELAY_MS = 3000;
const TERMINAL_STATUSES: DocumentStatus[] = ["EXTRACTED", "NEEDS_REVIEW", "FAILED"];

/**
 * Subscribes to `GET /api/v1/documents/{id}/events` (M7a T6) through the BFF proxy —
 * `EventSource` can't send an `Authorization` header, but it does send the session's httpOnly
 * cookie automatically as a same-origin credentialed request, which the proxy already reads.
 * Closes the connection once a terminal status arrives (the api's own stream also completes
 * itself then, but closing the client side too means no half-open connection lingers on a slow
 * network where the server's `complete()` hasn't been observed yet) and on unmount.
 */
export function useDocumentStatus(documentId: string | null): DocumentStatusState {
  const [state, setState] = useState<DocumentStatusState>({
    status: null,
    failureReason: null,
    connection: "connecting",
  });

  const reconnectTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

  useEffect(() => {
    if (!documentId) {
      return;
    }

    let cancelled = false;
    let source: EventSource | undefined;
    let lastStatus: DocumentStatus | null = null;

    function connect() {
      if (cancelled) return;
      setState((s) => ({ ...s, connection: "connecting" }));
      source = new EventSource(`/api/documents/${documentId}/events`);

      source.addEventListener("status", (event) => {
        if (cancelled) return;
        const payload = JSON.parse((event as MessageEvent).data) as StatusEventPayload;
        lastStatus = payload.status;
        setState({ status: payload.status, failureReason: payload.detail, connection: "open" });
        if (TERMINAL_STATUSES.includes(payload.status)) {
          source?.close();
        }
      });

      source.onopen = () => {
        if (!cancelled) {
          setState((s) => ({ ...s, connection: "open" }));
        }
      };

      source.onerror = () => {
        if (cancelled) return;
        source?.close();
        // A disconnect after a terminal status already arrived is the stream's own normal
        // close, not a stall — nothing further is coming either way, so no reconnect.
        if (lastStatus !== null && TERMINAL_STATUSES.includes(lastStatus)) {
          return;
        }
        setState((s) => ({ ...s, connection: "stalled" }));
        reconnectTimer.current = setTimeout(connect, RECONNECT_DELAY_MS);
      };
    }

    connect();

    return () => {
      cancelled = true;
      source?.close();
      if (reconnectTimer.current) {
        clearTimeout(reconnectTimer.current);
      }
    };
  }, [documentId]);

  return state;
}
