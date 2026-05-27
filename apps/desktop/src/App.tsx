import { AuthProvider, useAuth } from "@/hooks/use-auth";
import { RecordingProvider } from "@/hooks/use-recording-context";
import { LiveHighlightsProvider } from "@/hooks/use-live-highlights";
import { Sidebar } from "@/components/sidebar";
import { LoginPage } from "@/pages/login";
import { MeetingsPage } from "@/pages/meetings";
import { MeetingDetailPage } from "@/pages/meeting-detail";
import { RecordingPage } from "@/pages/recording";
import { SettingsPage } from "@/pages/settings";
import { ChatPage } from "@/pages/chat";
import { useState, useEffect } from "react";

function Router() {
  const { authenticated, loading } = useAuth();
  const [route, setRoute] = useState(window.location.hash);

  useEffect(() => {
    const handler = () => setRoute(window.location.hash);
    window.addEventListener("hashchange", handler);
    return () => window.removeEventListener("hashchange", handler);
  }, []);

  if (loading) {
    return (
      <div className="flex flex-col items-center justify-center h-full gap-3.5">
        <span className="inline-flex items-end gap-[3px]" style={{ height: 28 }}>
          {[0.35, 0.65, 1, 0.6, 0.4].map((h, i) => (
            <span
              key={i}
              style={{
                display: "block",
                width: 4,
                height: `${h * 100}%`,
                background: "var(--ink)",
                borderRadius: 2,
                animation: `dotPulse 1.4s ease-in-out ${i * 0.12}s infinite`,
              }}
            />
          ))}
        </span>
        <small style={{ fontSize: 12, color: "var(--muted)", letterSpacing: "0.04em" }}>
          Carregando NORA
        </small>
      </div>
    );
  }

  if (!authenticated) {
    return <LoginPage />;
  }

  const renderPage = () => {
    if (route === "#/chat") return <ChatPage />;
    if (route === "#/recording") return <RecordingPage />;
    if (route === "#/settings") return <SettingsPage />;
    const detailMatch = route?.match(/^#\/meetings\/([\w-]+)$/);
    if (detailMatch) return <MeetingDetailPage meetingId={detailMatch[1]} />;
    return <MeetingsPage />;
  };

  return (
    <RecordingProvider>
      <LiveHighlightsProvider>
        <div className="flex h-full">
          <Sidebar />
          {renderPage()}
        </div>
      </LiveHighlightsProvider>
    </RecordingProvider>
  );
}

export function App() {
  return (
    <AuthProvider>
      <Router />
    </AuthProvider>
  );
}
