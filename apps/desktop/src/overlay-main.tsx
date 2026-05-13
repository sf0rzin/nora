import React from "react";
import ReactDOM from "react-dom/client";
import { LiveHighlightsProvider } from "@/hooks/use-live-highlights";
import { OverlayPage } from "@/components/overlay";
import "./styles.css";

ReactDOM.createRoot(document.getElementById("overlay-root")!).render(
  <React.StrictMode>
    <LiveHighlightsProvider>
      <OverlayPage />
    </LiveHighlightsProvider>
  </React.StrictMode>,
);
