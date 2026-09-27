import type { NextConfig } from "next";

// The console renders nothing on the server that needs a credential. These
// three headers go on every page and proxied answer, and e2e/ops.spec.ts
// checks them. Next's own 308 for a trailing or doubled slash and its bare 500
// for a malformed percent escape carry none of them. The proxy passes none of
// the API's own security headers through and sets Cache-Control: no-store
// (lib/proxy.ts#relay). The rest of the console's server-side hardening is the
// checks in lib/proxy.ts#forward and lib/race.ts#runRace, which SECURITY.md
// lists under "The console".
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
