import { AuthProvider, useAuth } from "@/hooks/use-auth";
import { Sidebar } from "@/components/sidebar";
import { LoginPage } from "@/pages/login";
import { MeetingsPage } from "@/pages/meetings";
import { MeetingDetailPage } from "@/pages/meeting-detail";
import { RecordingPage } from "@/pages/recording";
import { SettingsPage } from "@/pages/settings";
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
      <div className="flex items-center justify-center h-full text-zinc-400">
        Carregando...
      </div>
    );
  }

  if (!authenticated) {
    return <LoginPage />;
  }

  const renderPage = () => {
    if (route === "#/recording") return <RecordingPage />;
    if (route === "#/settings") return <SettingsPage />;
    const detailMatch = route?.match(/^#\/meetings\/([\w-]+)$/);
    if (detailMatch) return <MeetingDetailPage meetingId={detailMatch[1]} />;
    return <MeetingsPage />;
  };

  return (
    <div className="flex h-full">
      <Sidebar />
      {renderPage()}
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
