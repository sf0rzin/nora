import React from "react";
import ReactDOM from "react-dom/client";
import { DockBar } from "@/components/dock-bar";
import "./styles.css";

ReactDOM.createRoot(document.getElementById("dock-root")!).render(
  <React.StrictMode>
    <DockBar />
  </React.StrictMode>,
);
