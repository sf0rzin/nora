/** @type {import('tailwindcss').Config} */
export default {
  content: ["./index.html", "./src/**/*.{js,ts,jsx,tsx}"],
  theme: {
    extend: {
      fontFamily: {
        sans: ['"DM Sans"', "ui-sans-serif", "system-ui", "-apple-system", "sans-serif"],
        display: ['"DM Sans"', "ui-sans-serif", "system-ui", "sans-serif"],
      },
      colors: {
        canvas: "#FDFDFC",
        sidebar: "#F7F7F5",
        chip: "#F0F0EE",
        ink: "#15171A",
        muted: "#6E7178",
        border: { DEFAULT: "#E7E7E3", strong: "#D8D8D2" },
        accent: {
          DEFAULT: "oklch(0.58 0.16 248)",
          ink: "oklch(0.48 0.18 248)",
          soft: "oklch(0.95 0.04 248)",
          dim: "oklch(0.7 0.12 248)",
        },
        ok: { DEFAULT: "#62b585", ink: "#3f8a5e" },
        warn: { DEFAULT: "#d4a04c", ink: "#a37528" },
        danger: { DEFAULT: "#c97766", ink: "#a04c3e" },
      },
      borderColor: {
        DEFAULT: "#E7E7E3",
      },
      borderRadius: {
        sm: "6px",
        DEFAULT: "9px",
        md: "12px",
        lg: "16px",
        pill: "999px",
      },
      boxShadow: {
        sm: "0 1px 2px rgba(15, 23, 42, 0.04), 0 1px 3px rgba(15, 23, 42, 0.06)",
        md: "0 4px 12px -4px rgba(15, 23, 42, 0.10), 0 2px 6px -4px rgba(15, 23, 42, 0.08)",
        lg: "0 18px 42px -20px rgba(15, 23, 42, 0.28), 0 4px 12px rgba(15, 23, 42, 0.06)",
        xl: "0 32px 80px -24px rgba(15, 23, 42, 0.32), 0 8px 22px rgba(15, 23, 42, 0.06)",
      },
      transitionTimingFunction: {
        brand: "cubic-bezier(0.22, 1, 0.36, 1)",
        expo: "cubic-bezier(0.16, 1, 0.3, 1)",
      },
      letterSpacing: {
        tightish: "-0.005em",
        tightui: "-0.012em",
        tighthead: "-0.022em",
        tightxl: "-0.025em",
        wideish: "0.04em",
        widelabel: "0.08em",
      },
    },
  },
  plugins: [require("@tailwindcss/typography")],
};
