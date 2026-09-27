import { fileURLToPath } from "node:url";
import { defineConfig } from "vitest/config";

export default defineConfig({
  resolve: {
    alias: { "@": fileURLToPath(new URL(".", import.meta.url)) },
  },
  test: {
    include: ["lib/**/*.test.ts", "components/**/*.test.tsx", "app/**/*.test.ts"],
    exclude: ["e2e/**", "node_modules/**"],
    environment: "node",
    restoreMocks: true,
  },
});
