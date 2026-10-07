import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import App from "./App.jsx";
import ClientErrorBoundary from "./components/ClientErrorBoundary.jsx";
import { reportClientFailure } from "./api/diagnostics.js";
import "./styles/global.css";
import "./styles/layout.css";
import "./styles/pages.css";
import "./styles/onboarding.css";
import "./styles/announcements.css";

createRoot(document.getElementById("root"), {
  onCaughtError: (error) => reportClientFailure("RENDER_ERROR", error),
  onUncaughtError: (error) => reportClientFailure("RENDER_ERROR", error),
  onRecoverableError: (error) => reportClientFailure("RECOVERABLE_RENDER_ERROR", error),
}).render(
  <StrictMode>
    <BrowserRouter>
      <ClientErrorBoundary><App /></ClientErrorBoundary>
    </BrowserRouter>
  </StrictMode>,
);
