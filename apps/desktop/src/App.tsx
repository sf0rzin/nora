import { AuthProvider, useAuth } from "@/hooks/use-auth";
import { RecordingProvider } from "@/hooks/use-recording-context";
import { LiveHighlightsProvider } from "@/hooks/use-live-highlights";
import { ActiveRecordingProvider } from "@/hooks/use-active-recording";
import { Sidebar } from "@/components/sidebar";
import { Titlebar } from "@/components/titlebar";
import { ResizeHandles } from "@/components/resize-handles";
import { NewMeetingModal } from "@/components/new-meeting-modal";
import { LoginPage } from "@/pages/login";
import { MeetingsPage } from "@/pages/meetings";
import { MeetingDetailPage } from "@/pages/meeting-detail";
import { SettingsPage } from "@/pages/settings";
import { ChatPage } from "@/pages/chat";
import { useState, useEffect } from "react";

const MODAL_EVENT = "nora-new-meeting";

export function openNewMeetingModal() {
  window.dispatchEvent(new CustomEvent(MODAL_EVENT));
}

function Router() {
  const { authenticated, loading } = useAuth();
  const [route, setRoute] = useState(window.location.hash);
  const [modalOpen, setModalOpen] = useState(false);

  useEffect(() => {
    const handler = () => setRoute(window.location.hash);
    window.addEventListener("hashchange", handler);
    return () => window.removeEventListener("hashchange", handler);
  }, []);

  useEffect(() => {
    const open = () => setModalOpen(true);
    window.addEventListener(MODAL_EVENT, open);
    return () => window.removeEventListener(MODAL_EVENT, open);
  }, []);

  // Global "N" shortcut to open the new meeting modal
  useEffect(() => {
    if (!authenticated) return;
    const onKey = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement | null;
      const typing =
        target &&
        (target.tagName === "INPUT" ||
          target.tagName === "TEXTAREA" ||
          target.isContentEditable);
      if (typing) return;
      if ((e.key === "n" || e.key === "N") && !e.metaKey && !e.ctrlKey) {
        e.preventDefault();
        setModalOpen(true);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [authenticated]);

  const renderPage = () => {
    if (route === "#/chat") return <ChatPage />;
    if (route === "#/settings") return <SettingsPage />;
    const detailMatch = route?.match(/^#\/meetings\/([\w-]+)$/);
    if (detailMatch) return <MeetingDetailPage meetingId={detailMatch[1]} />;
    return <MeetingsPage />;
  };

  let content: React.ReactNode;
  if (loading) {
    content = (
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
  } else if (!authenticated) {
    content = <LoginPage />;
  } else {
    content = (
      <RecordingProvider>
        <LiveHighlightsProvider>
          <ActiveRecordingProvider>
            <div className="flex h-full">
              <Sidebar />
              {renderPage()}
            </div>
            <NewMeetingModal open={modalOpen} onClose={() => setModalOpen(false)} />
          </ActiveRecordingProvider>
        </LiveHighlightsProvider>
      </RecordingProvider>
    );
  }

  // Barra de título custom (janela sem decorações nativas) acima de TODA tela
  // da janela principal — loading, login e app — já que sem ela não há como
  // mover/fechar a janela.
  return (
    <div className="flex flex-col h-full">
      <Titlebar />
      <div className="flex-1 min-h-0">{content}</div>
      {/* Pegas de resize (janela sem borda nativa). */}
      <ResizeHandles />
    </div>
  );
}

export function App() {
  return (
    <AuthProvider>
      <Router />
    </AuthProvider>
  );
}
