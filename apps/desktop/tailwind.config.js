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
        border: "#E7E7E3",
        accent: {
          DEFAULT: "oklch(0.58 0.16 248)",
          ink: "oklch(0.48 0.18 248)",
          soft: "oklch(0.95 0.04 248)",
        },
        ok: { DEFAULT: "#62b585", ink: "#3f8a5e" },
        warn: { DEFAULT: "#d4a04c", ink: "#a37528" },
        danger: { DEFAULT: "#c97766", ink: "#a04c3e" },
      },
      borderColor: {
        DEFAULT: "#E7E7E3",
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
