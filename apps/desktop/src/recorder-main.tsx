import React from "react";
import ReactDOM from "react-dom/client";
import { RecordingProvider } from "@/hooks/use-recording-context";
import { LiveHighlightsProvider } from "@/hooks/use-live-highlights";
import { Recorder } from "@/components/recorder";
import "./styles.css";

// Janela nativa "recorder": grava mic + áudio do sistema com transcrição ao
// vivo (recurso que o web em nora.systems não tem). Reusa a orquestração já
// testada do hook useRecording, que depende de RecordingProvider +
// LiveHighlightsProvider — daí a árvore replicar a do App principal.
ReactDOM.createRoot(document.getElementById("recorder-root")!).render(
  <React.StrictMode>
    <RecordingProvider>
      <LiveHighlightsProvider>
        <Recorder />
      </LiveHighlightsProvider>
    </RecordingProvider>
  </React.StrictMode>,
);
