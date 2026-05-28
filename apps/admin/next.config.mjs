/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  // Console interno — standalone facilita imagem Docker enxuta.
  output: "standalone",
};

export default nextConfig;
