import { useAuth } from "@/hooks/use-auth";

const navItems = [
  { label: "Reuniões", href: "#/meetings", icon: "📋" },
  { label: "Gravar", href: "#/recording", icon: "🎙️" },
  { label: "Config", href: "#/settings", icon: "⚙️" },
];

export function Sidebar() {
  const { user, logout } = useAuth();

  return (
    <aside className="w-56 h-full bg-zinc-800 border-r border-zinc-700 flex flex-col shrink-0">
      <div className="p-4 border-b border-zinc-700">
        <h2 className="text-lg font-bold">NORA</h2>
        <p className="text-xs text-zinc-400 truncate">{user?.email}</p>
      </div>

      <nav className="flex-1 p-2 space-y-1">
        {navItems.map((item) => (
          <a
            key={item.href}
            href={item.href}
            className="flex items-center gap-3 px-3 py-2 text-sm rounded hover:bg-zinc-700 transition-colors"
          >
            <span>{item.icon}</span>
            {item.label}
          </a>
        ))}
      </nav>

      <div className="p-2 border-t border-zinc-700">
        <button
          onClick={logout}
          className="w-full text-left px-3 py-2 text-sm text-zinc-400 hover:text-zinc-100 hover:bg-zinc-700 rounded transition-colors"
        >
          Sair
        </button>
      </div>
    </aside>
  );
}
