import type { NextConfig } from "next";

// The console renders nothing on the server that needs a credential, so these
// headers are the whole of its server-side hardening. They go on every answer,
// proxied ones included: the proxy passes none of the API's own security headers
// through (lib/proxy.ts), and e2e/ops.spec.ts checks them.
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
