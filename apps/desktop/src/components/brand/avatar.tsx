function initialsOf(name: string) {
  if (name === "Você" || name === "Eu") return "Eu";
  const parts = name.replace(/\(.*?\)/g, "").trim().split(/\s+/);
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
}

function colorOf(name: string) {
  let h = 0;
  for (let i = 0; i < name.length; i++) {
    h = (h * 31 + name.charCodeAt(i)) >>> 0;
  }
  return `oklch(0.82 0.07 ${h % 360})`;
}

const ME_GRADIENT =
  "radial-gradient(circle at 30% 25%, #d8f0f6 0%, transparent 38%), " +
  "radial-gradient(circle at 78% 62%, #e8b8d8 0%, transparent 45%), " +
  "radial-gradient(circle at 22% 78%, #3a4a4f 0%, transparent 30%), " +
  "radial-gradient(circle at 60% 40%, #4ec4d8 8%, #6aa8d8 45%, #9778b8 100%)";

interface AvatarProps {
  name: string;
  size?: number;
  me?: boolean;
  ringColor?: string;
}

export function Avatar({
  name,
  size = 24,
  me,
  ringColor = "var(--canvas)",
}: AvatarProps) {
  const isMe = me ?? (name === "Você" || name === "Eu");
  if (isMe) {
    return (
      <span
        title={name}
        className="rounded-full shrink-0 block"
        style={{
          width: size,
          height: size,
          background: ME_GRADIENT,
          boxShadow: `0 0 0 2px ${ringColor}, inset 0 0 6px rgba(255,255,255,0.4)`,
          filter: "saturate(1.15)",
        }}
      />
    );
  }
  return (
    <span
      title={name}
      className="rounded-full shrink-0 grid place-items-center"
      style={{
        width: size,
        height: size,
        background: colorOf(name),
        color: "rgba(0,0,0,0.62)",
        boxShadow: `0 0 0 2px ${ringColor}`,
        fontSize: size * 0.4,
        fontWeight: 500,
      }}
    >
      {initialsOf(name)}
    </span>
  );
}

export function AvatarStack({
  names,
  max = 3,
  size = 22,
}: {
  names: string[];
  max?: number;
  size?: number;
}) {
  const shown = names.slice(0, max);
  const overflow = names.length - shown.length;
  return (
    <div className="flex items-center">
      <div className="flex">
        {shown.map((n, i) => (
          <div key={i} style={{ marginLeft: i === 0 ? 0 : -7 }}>
            <Avatar name={n} size={size} />
          </div>
        ))}
      </div>
      {overflow > 0 && (
        <span
          className="rounded-full grid place-items-center font-medium"
          style={{
            marginLeft: -7,
            width: size,
            height: size,
            background: "var(--chip)",
            border: "2px solid var(--canvas)",
            fontSize: size * 0.42,
            color: "var(--muted)",
          }}
        >
          +{overflow}
        </span>
      )}
    </div>
  );
}
