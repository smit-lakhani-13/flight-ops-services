import { describe, expect, it } from "vitest";
import * as route from "./route";

// A method this module does not export is answered by Next itself: a bare
// 405 with no body. Exporting every method a browser can send keeps each
// refusal in the console's envelope instead.
describe("the /api route", () => {
  it("hands every method but OPTIONS to the proxy", () => {
    for (const method of ["GET", "HEAD", "POST", "PUT", "PATCH", "DELETE"] as const) {
      expect(route[method], method).toBe(route.GET);
    }
  });

  it("refuses a PUT in the envelope, with the path's Allow header", async () => {
    const request = new Request("http://console.test/api/v1/flights/UA123", { method: "PUT", body: "{}" });
    const response = await route.PUT(request, { params: Promise.resolve({ path: ["v1", "flights", "UA123"] }) });
    expect(response.status).toBe(405);
    expect(response.headers.get("allow")).toBe("GET, HEAD, POST, PATCH, DELETE");
    expect(await response.json()).toMatchObject({ code: "CONSOLE_METHOD_REFUSED" });
  });
});
