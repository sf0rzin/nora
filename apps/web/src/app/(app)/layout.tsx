import CoreShell from "@/components/core/core-shell";

export default function AppLayout({ children }: { children: React.ReactNode }) {
  return <CoreShell>{children}</CoreShell>;
}
