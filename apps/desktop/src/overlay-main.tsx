import React from "react";
import ReactDOM from "react-dom/client";
import { OverlayPage } from "@/components/overlay";
import "./styles.css";

ReactDOM.createRoot(document.getElementById("overlay-root")!).render(
  <React.StrictMode>
    <OverlayPage />
  </React.StrictMode>,
);
