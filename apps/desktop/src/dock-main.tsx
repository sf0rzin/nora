import React from "react";
import ReactDOM from "react-dom/client";
import { DockBar } from "@/components/dock-bar";
import { RecordingProvider } from "@/hooks/use-recording-context";
import { LiveHighlightsProvider } from "@/hooks/use-live-highlights";
import "./styles.css";

// The dock now controls the native recording via useRecording, which depends on
// RecordingProvider (transcript state) + LiveHighlightsProvider (live
// analysis). Without those providers the hook throws on mount. The same providers
// wrap the overlay, so both windows share the orchestration.
ReactDOM.createRoot(document.getElementById("dock-root")!).render(
  <React.StrictMode>
    <RecordingProvider>
      <LiveHighlightsProvider>
        <DockBar />
      </LiveHighlightsProvider>
    </RecordingProvider>
  </React.StrictMode>,
);
