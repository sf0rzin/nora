import { useState, useEffect } from "react";
import { useAuth } from "@/hooks/use-auth";
import { secrets, SECRET_KEYS } from "@/lib/secrets";
import { invoke } from "@tauri-apps/api/core";
import { Avatar } from "@/components/brand/avatar";

type SectionId = "profile" | "privacy" | "audio" | "about";

const SECTIONS: { id: SectionId; label: string }[] = [
  { id: "profile", label: "Perfil" },
  { id: "privacy", label: "Privacidade" },
  { id: "audio", label: "Áudio & captura" },
  { id: "about", label: "Sobre" },
];

function Btn({
  children,
  onClick,
  variant = "default",
  size = "md",
  disabled,
}: {
  children: React.ReactNode;
  onClick?: () => void;
  variant?: "default" | "primary" | "ghost" | "danger";
  size?: "sm" | "md";
  disabled?: boolean;
}) {
  const styles = {
    default: { bg: "var(--canvas)", color: "var(--ink)", border: "var(--border)" },
    primary: { bg: "var(--ink)", color: "var(--canvas)", border: "var(--ink)" },
    ghost: { bg: "transparent", color: "var(--ink)", border: "transparent" },
    danger: {
      bg: "var(--canvas)",
      color: "var(--danger-ink)",
      border: "rgba(201, 119, 102, 0.40)",
    },
  };
  const s = styles[variant];
  const pad = size === "sm" ? "5px 11px" : "8px 14px";
  const fs = size === "sm" ? 12 : 13;
  return (
    <button
      onClick={onClick}
      disabled={disabled}
      style={{
        padding: pad,
        background: s.bg,
        color: s.color,
        border: `1px solid ${s.border}`,
        borderRadius: 7,
        cursor: disabled ? "not-allowed" : "pointer",
        opacity: disabled ? 0.5 : 1,
        fontSize: fs,
        fontWeight: 500,
        letterSpacing: "-0.005em",
        transition: "background 120ms ease, border-color 120ms ease",
      }}
    >
      {children}
    </button>
  );
}

function Toggle({
  checked,
  onChange,
  disabled,
}: {
  checked: boolean;
  onChange: (v: boolean) => void;
  disabled?: boolean;
}) {
  return (
    <button
      type="button"
      onClick={() => !disabled && onChange(!checked)}
      disabled={disabled}
      aria-pressed={checked}
      style={{
        width: 34,
        height: 20,
        borderRadius: 999,
        background: checked ? "var(--accent)" : "var(--chip)",
        border: "none",
        cursor: disabled ? "not-allowed" : "pointer",
        padding: 0,
        position: "relative",
        transition: "background 160ms ease",
        flexShrink: 0,
        opacity: disabled ? 0.5 : 1,
      }}
    >
      <span
        style={{
          position: "absolute",
          top: 2,
          left: checked ? 16 : 2,
          width: 16,
          height: 16,
          borderRadius: "50%",
          background: "white",
          boxShadow: "0 1px 2px rgba(0,0,0,0.12)",
          transition: "left 160ms ease",
        }}
      />
    </button>
  );
}

function ToggleRow({
  label,
  helper,
  checked,
  onChange,
  disabled,
  tag,
}: {
  label: string;
  helper?: React.ReactNode;
  checked: boolean;
  onChange: (v: boolean) => void;
  disabled?: boolean;
  tag?: string;
}) {
  return (
    <div
      className="flex items-start justify-between gap-4"
      style={{ padding: "12px 0", borderBottom: "1px solid var(--border)" }}
    >
      <div className="flex-1 min-w-0">
        <div className="flex items-center gap-2">
          <span style={{ fontSize: 13.5, color: "var(--ink)", letterSpacing: "-0.005em" }}>
            {label}
          </span>
          {tag && (
            <span
              style={{
                fontSize: 10,
                padding: "1px 7px",
                borderRadius: 999,
                background: "var(--chip)",
                color: "var(--muted)",
                letterSpacing: "0.06em",
                textTransform: "uppercase",
                fontWeight: 500,
              }}
            >
              {tag}
            </span>
          )}
        </div>
        {helper && (
          <div
            style={{
              fontSize: 11.5,
              color: "var(--muted)",
              marginTop: 4,
              lineHeight: 1.5,
            }}
          >
            {helper}
          </div>
        )}
      </div>
      <Toggle checked={checked} onChange={onChange} disabled={disabled} />
    </div>
  );
}

function InfoRow({
  label,
  value,
  mono,
}: {
  label: string;
  value: React.ReactNode;
  mono?: boolean;
}) {
  return (
    <div
      className="flex items-center justify-between gap-4"
      style={{ padding: "10px 0", borderBottom: "1px solid var(--border)", fontSize: 13 }}
    >
      <span style={{ color: "var(--muted)" }}>{label}</span>
      <span
        style={{
          color: "var(--ink)",
          fontVariantNumeric: mono ? "tabular-nums" : undefined,
          fontFamily: mono ? "ui-monospace, monospace" : undefined,
          fontSize: mono ? 12 : 13,
        }}
      >
        {value}
      </span>
    </div>
  );
}

function SectionWrap({
  label,
  right,
  children,
}: {
  label: string;
  right?: React.ReactNode;
  children: React.ReactNode;
}) {
  return (
    <section style={{ marginBottom: 36 }}>
      <div
        className="flex items-baseline justify-between"
        style={{ marginBottom: 14, paddingBottom: 8, borderBottom: "1px solid var(--border)" }}
      >
        <h3
          style={{
            fontSize: 11,
            fontWeight: 500,
            letterSpacing: "0.08em",
            textTransform: "uppercase",
            color: "var(--muted)",
            margin: 0,
          }}
        >
          {label}
        </h3>
        {right && <span style={{ fontSize: 11, color: "var(--muted)" }}>{right}</span>}
      </div>
      {children}
    </section>
  );
}

function SectionHeader({
  title,
  subtitle,
}: {
  title: string;
  subtitle?: string;
}) {
  return (
    <div style={{ marginBottom: 32 }}>
      <h1
        style={{
          fontFamily: "var(--display)",
          fontSize: 28,
          fontWeight: 500,
          letterSpacing: "-0.025em",
          color: "var(--ink)",
          margin: "0 0 8px",
          lineHeight: 1.15,
        }}
      >
        {title}
      </h1>
      {subtitle && (
        <p style={{ fontSize: 13.5, color: "var(--muted)", margin: 0, lineHeight: 1.55 }}>
          {subtitle}
        </p>
      )}
    </div>
  );
}

function ProfileSection() {
  const { user, logout } = useAuth();
  return (
    <>
      <SectionHeader title="Perfil" subtitle="Como você aparece no NORA." />

      <div
        className="flex items-center gap-5"
        style={{ paddingBottom: 24, marginBottom: 8, borderBottom: "1px solid var(--border)" }}
      >
        <Avatar name={user?.displayName || "Você"} size={64} me />
        <div>
          <div style={{ fontSize: 16, fontWeight: 500, color: "var(--ink)", letterSpacing: "-0.012em" }}>
            {user?.displayName || "—"}
          </div>
          <div style={{ fontSize: 13, color: "var(--muted)", marginTop: 2 }}>
            {user?.email || ""}
          </div>
        </div>
      </div>

      <SectionWrap label="Identidade">
        <InfoRow label="Nome" value={user?.displayName || "—"} />
        <InfoRow label="E-mail" value={user?.email || "—"} />
        <InfoRow label="Tenant" value={user?.tenantId || "—"} mono />
        <InfoRow
          label="Roles"
          value={user?.roles?.length ? user.roles.join(", ") : "—"}
        />
      </SectionWrap>

      <SectionWrap label="Sessão">
        <div
          className="flex items-center justify-between"
          style={{ padding: "14px 0" }}
        >
          <div>
            <div style={{ fontSize: 13.5, color: "var(--ink)" }}>Sair desta máquina</div>
            <div style={{ fontSize: 11.5, color: "var(--muted)", marginTop: 3 }}>
              Limpa o token armazenado no keyring.
            </div>
          </div>
          <Btn variant="danger" onClick={logout}>
            Encerrar sessão
          </Btn>
        </div>
      </SectionWrap>
    </>
  );
}

function PrivacySection() {
  const [stealthMode, setStealthMode] = useState(false);
  const [platform, setPlatform] = useState<string | null>(null);
  const [stealthError, setStealthError] = useState<string | null>(null);

  useEffect(() => {
    invoke<{ platform: string }>("check_system_audio_prerequisites")
      .then(async (prereq) => {
        setPlatform(prereq.platform);
        if (prereq.platform === "windows") {
          try {
            const saved = await invoke<boolean>("get_stealth_mode");
            setStealthMode(saved);
          } catch (e) {
            console.error("[settings] stealth load:", e);
          }
        }
      })
      .catch((e) => console.error("[settings] platform detect:", e));
  }, []);

  const isWindows = platform === "windows";
  const isSupported = isWindows;

  const handleStealthToggle = async (enabled: boolean) => {
    if (!isSupported) return;
    try {
      await invoke("set_stealth_mode", { enabled });
      setStealthMode(enabled);
      setStealthError(null);
    } catch (e) {
      console.error("[settings] stealth set:", e);
      setStealthError("Não foi possível alterar o modo stealth. Tente novamente.");
    }
  };

  const stealthHelper =
    platform === "macos" ? (
      <>
        Disponível no Windows. Implementação para macOS via{" "}
        <code style={{ background: "var(--chip)", padding: "1px 5px", borderRadius: 4, fontSize: 11.5 }}>
          setSharingType(.none)
        </code>{" "}
        do NSWindow está planejada para uma versão futura.
      </>
    ) : platform === "linux" ? (
      <>
        Indisponível no Linux. O display server (X11/Wayland) não oferece um mecanismo
        padronizado pra uma aplicação se auto-excluir de capturas de tela. Alternativa:
        minimize a janela do NORA durante o compartilhamento.
      </>
    ) : isWindows ? (
      <>
        Oculta o NORA Desktop e a overlay de capturas de tela, OBS e compartilhamento.
        Usa a API nativa do Windows (<code style={{ background: "var(--chip)", padding: "1px 5px", borderRadius: 4, fontSize: 11.5 }}>SetWindowDisplayAffinity</code>).
      </>
    ) : (
      <>Detecção de plataforma em andamento…</>
    );

  return (
    <>
      <SectionHeader
        title="Privacidade e LGPD"
        subtitle="Como o NORA Desktop protege sua tela e seus dados."
      />

      <SectionWrap label="Modo stealth">
        <ToggleRow
          label="Esconder NORA de capturas de tela"
          helper={stealthHelper}
          checked={stealthMode}
          onChange={handleStealthToggle}
          disabled={!isSupported}
          tag={platform === null ? undefined : !isSupported ? (platform === "macos" ? "Em breve" : "Indisponível") : undefined}
        />
        {stealthError && (
          <div style={{ marginTop: 8, fontSize: 12, color: "var(--danger-ink)" }}>
            {stealthError}
          </div>
        )}
      </SectionWrap>

      <SectionWrap label="PII Shield">
        <div style={{ fontSize: 12.5, color: "var(--muted)", lineHeight: 1.6, marginBottom: 10 }}>
          Toda transcrição passa pelo PII Shield antes de chegar ao LLM. CPF, CNPJ,
          e-mail, telefone, cartão de crédito e nomes brasileiros viram placeholders{" "}
          <code style={{ background: "var(--chip)", padding: "1px 5px", borderRadius: 4, fontSize: 11.5 }}>
            [[CPF_1]]
          </code>
          ,{" "}
          <code style={{ background: "var(--chip)", padding: "1px 5px", borderRadius: 4, fontSize: 11.5 }}>
            [[EMAIL_2]]
          </code>{" "}
          etc.
        </div>
        <div
          className="flex items-center gap-2.5"
          style={{
            padding: "10px 14px",
            background: "rgba(98, 181, 133, 0.10)",
            border: "1px solid rgba(98, 181, 133, 0.30)",
            borderRadius: 8,
            fontSize: 13,
          }}
        >
          <span style={{ width: 8, height: 8, borderRadius: "50%", background: "#62b585" }} />
          <span style={{ color: "var(--success-ink)" }}>PII Shield ativo (gerenciado pelo NORA)</span>
        </div>
      </SectionWrap>
    </>
  );
}

function AudioSection() {
  const [cleanupDone, setCleanupDone] = useState(false);

  useEffect(() => {
    (async () => {
      try {
        const hasOldKey = await secrets.has(SECRET_KEYS.LEGACY_AZURE_SPEECH_KEY);
        if (hasOldKey) await secrets.delete(SECRET_KEYS.LEGACY_AZURE_SPEECH_KEY);
        const hasOldRegion = await secrets.has(SECRET_KEYS.LEGACY_AZURE_REGION);
        if (hasOldRegion) await secrets.delete(SECRET_KEYS.LEGACY_AZURE_REGION);
        setCleanupDone(true);
      } catch (e) {
        console.error("[settings] secret migration:", e);
        setCleanupDone(true);
      }
    })();
  }, []);

  return (
    <>
      <SectionHeader
        title="Áudio & captura"
        subtitle="Configurações da pipeline de transcrição em tempo real."
      />

      <SectionWrap label="Azure Speech-to-Text">
        <div
          className="flex items-center gap-3 mb-3"
          style={{
            padding: "10px 14px",
            background: "rgba(98, 181, 133, 0.10)",
            border: "1px solid rgba(98, 181, 133, 0.30)",
            borderRadius: 8,
            fontSize: 13,
          }}
        >
          <span style={{ width: 8, height: 8, borderRadius: "50%", background: "#62b585" }} />
          <span style={{ color: "var(--success-ink)" }}>
            Transcrição gerenciada pelo NORA
          </span>
        </div>
        <p style={{ fontSize: 12.5, color: "var(--muted)", lineHeight: 1.6 }}>
          Nenhuma configuração necessária. O NORA gerencia automaticamente os tokens de
          autorização para o Azure Speech Services usando o Token Broker do backend (ADR 0009).
        </p>
        {cleanupDone && (
          <p style={{ fontSize: 11.5, color: "var(--muted)", marginTop: 8 }}>
            Chaves antigas de configuração foram removidas com sucesso.
          </p>
        )}
      </SectionWrap>

      <SectionWrap label="Captura de áudio do sistema">
        <p style={{ fontSize: 12.5, color: "var(--muted)", lineHeight: 1.6, marginBottom: 12 }}>
          Pra capturar áudio de reuniões remotas (Meet, Zoom, Teams), o NORA combina o
          microfone com o áudio do sistema. Em cada plataforma o suporte é diferente:
        </p>
        <ul className="flex flex-col gap-2 m-0 p-0" style={{ listStyle: "none", fontSize: 12.5, color: "var(--ink)" }}>
          <li className="flex items-start gap-2.5">
            <span style={{ width: 5, height: 5, borderRadius: "50%", background: "var(--accent)", marginTop: 8 }} />
            <span>
              <strong style={{ fontWeight: 500 }}>Windows:</strong>{" "}
              <span style={{ color: "var(--muted)" }}>WASAPI loopback nativo (zero config)</span>
            </span>
          </li>
          <li className="flex items-start gap-2.5">
            <span style={{ width: 5, height: 5, borderRadius: "50%", background: "var(--accent)", marginTop: 8 }} />
            <span>
              <strong style={{ fontWeight: 500 }}>Linux:</strong>{" "}
              <span style={{ color: "var(--muted)" }}>PulseAudio/PipeWire monitor (auto-detecta)</span>
            </span>
          </li>
          <li className="flex items-start gap-2.5">
            <span style={{ width: 5, height: 5, borderRadius: "50%", background: "var(--accent)", marginTop: 8 }} />
            <span>
              <strong style={{ fontWeight: 500 }}>macOS:</strong>{" "}
              <span style={{ color: "var(--muted)" }}>requer driver virtual (BlackHole) ou ScreenCaptureKit (macOS 13+)</span>
            </span>
          </li>
        </ul>
      </SectionWrap>
    </>
  );
}

function AboutSection() {
  return (
    <>
      <SectionHeader title="Sobre o NORA Desktop" subtitle="Informações do build atual." />

      <SectionWrap label="Build">
        <InfoRow label="Versão" value="0.1.0" mono />
        <InfoRow label="Canal" value="dev" mono />
        <InfoRow label="Stack" value="Tauri 2 · React 18 · Rust" />
      </SectionWrap>

      <SectionWrap label="Documentação">
        <div
          className="flex items-center justify-between"
          style={{ padding: "14px 0" }}
        >
          <div>
            <div style={{ fontSize: 13.5, color: "var(--ink)" }}>ADRs e arquitetura</div>
            <div style={{ fontSize: 11.5, color: "var(--muted)", marginTop: 3 }}>
              Decisões arquiteturais em <code style={{ background: "var(--chip)", padding: "1px 5px", borderRadius: 4, fontSize: 11.5 }}>docs/adr/</code>.
            </div>
          </div>
        </div>
      </SectionWrap>
    </>
  );
}

export function SettingsPage() {
  const { user } = useAuth();
  const [section, setSection] = useState<SectionId>("profile");

  return (
    <main className="flex-1 h-full flex" style={{ background: "var(--canvas)", minWidth: 0 }}>
      <aside
        className="shrink-0 flex flex-col gap-1"
        style={{
          width: 220,
          borderRight: "1px solid var(--border)",
          background: "var(--canvas)",
          padding: "44px 14px 20px",
        }}
      >
        <a
          href="#/meetings"
          className="inline-flex items-center gap-1.5 w-fit"
          style={{
            padding: "0 8px 12px",
            color: "var(--muted)",
            fontSize: 12,
            cursor: "pointer",
            background: "transparent",
            border: "none",
          }}
          onMouseEnter={(e) => (e.currentTarget.style.color = "var(--ink)")}
          onMouseLeave={(e) => (e.currentTarget.style.color = "var(--muted)")}
        >
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <polyline points="15 18 9 12 15 6" />
          </svg>
          Voltar
        </a>
        <div style={{ padding: "0 8px 14px" }}>
          <h2
            style={{
              fontFamily: "var(--display)",
              fontSize: 17,
              fontWeight: 500,
              letterSpacing: "-0.018em",
              margin: 0,
              color: "var(--ink)",
            }}
          >
            Configurações
          </h2>
          <div style={{ fontSize: 11.5, color: "var(--muted)", marginTop: 2 }}>
            {user?.email || ""}
          </div>
        </div>
        {SECTIONS.map((s) => {
          const active = section === s.id;
          return (
            <button
              key={s.id}
              onClick={() => setSection(s.id)}
              className="text-left"
              style={{
                padding: "8px 10px",
                background: active ? "var(--accent-soft)" : "transparent",
                border: "none",
                borderRadius: 7,
                cursor: "pointer",
                fontSize: 13,
                color: active ? "var(--accent-ink)" : "var(--ink)",
                letterSpacing: "-0.005em",
              }}
              onMouseEnter={(e) => {
                if (!active) e.currentTarget.style.background = "rgba(0,0,0,0.03)";
              }}
              onMouseLeave={(e) => {
                e.currentTarget.style.background = active
                  ? "var(--accent-soft)"
                  : "transparent";
              }}
            >
              {s.label}
            </button>
          );
        })}
      </aside>

      <div className="flex-1 overflow-y-auto" style={{ minWidth: 0 }}>
        <div style={{ maxWidth: 680, margin: "0 auto", padding: "56px 48px 96px" }}>
          {section === "profile" && <ProfileSection />}
          {section === "privacy" && <PrivacySection />}
          {section === "audio" && <AudioSection />}
          {section === "about" && <AboutSection />}

          <div
            className="flex items-center justify-between"
            style={{
              marginTop: 56,
              paddingTop: 18,
              borderTop: "1px solid var(--border)",
              fontSize: 11.5,
              color: "var(--muted)",
            }}
          >
            <span>Alterações são salvas automaticamente.</span>
            <span>NORA Desktop · 0.1.0</span>
          </div>
        </div>
      </div>
    </main>
  );
}
