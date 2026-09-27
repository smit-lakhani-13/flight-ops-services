import type { NextConfig } from "next";

// The console renders nothing on the server that needs a credential, so these
// headers are the whole of its server-side hardening. The API it talks to keeps
// its own security headers; the proxy passes only an allow-list of them through.
const securityHeaders = [
  { key: "X-Content-Type-Options", value: "nosniff" },
  { key: "Referrer-Policy", value: "no-referrer" },
  { key: "X-Frame-Options", value: "DENY" },
];

const nextConfig: NextConfig = {
  reactStrictMode: true,
  poweredByHeader: false,
  async headers() {
    return [{ source: "/:path*", headers: securityHeaders }];
  },
};

export default nextConfig;
