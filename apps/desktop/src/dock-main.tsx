import React from "react";
import ReactDOM from "react-dom/client";
import { DockBar } from "@/components/dock-bar";
import { RecordingProvider } from "@/hooks/use-recording-context";
import { LiveHighlightsProvider } from "@/hooks/use-live-highlights";
import "./styles.css";

// A dock agora controla a gravação nativa via useRecording, que depende do
// RecordingProvider (estado do transcript) + LiveHighlightsProvider (análise ao
// vivo). Sem esses providers o hook lança no mount. Os mesmos providers
// envolvem a overlay, então as duas janelas compartilham a orquestração.
ReactDOM.createRoot(document.getElementById("dock-root")!).render(
  <React.StrictMode>
    <RecordingProvider>
      <LiveHighlightsProvider>
        <DockBar />
      </LiveHighlightsProvider>
    </RecordingProvider>
  </React.StrictMode>,
);
