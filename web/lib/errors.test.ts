import { describe, expect, it } from "vitest";
import { classify, hint } from "./errors";

describe("classify", () => {
  it("sorts the API's envelopes into kinds and keeps the code and message", () => {
    expect(classify(401, { code: "UNAUTHENTICATED", message: "Authentication is required" })).toMatchObject({
      kind: "unauthenticated",
      code: "UNAUTHENTICATED",
      message: "Authentication is required",
    });
    expect(classify(403, { code: "FORBIDDEN", message: "no" }).kind).toBe("forbidden");
    expect(classify(404, { code: "FLIGHT_NOT_FOUND", message: "no" }).kind).toBe("notFound");
    expect(classify(409, { code: "ILLEGAL_STATUS_TRANSITION", message: "no" }).kind).toBe("conflict");
    expect(classify(400, { code: "MALFORMED_REQUEST", message: "no" }).kind).toBe("rejected");
    expect(classify(415, { code: "UNSUPPORTED_MEDIA_TYPE", message: "no" }).kind).toBe("rejected");
    expect(classify(500, { code: "INTERNAL_ERROR", message: "no" }).kind).toBe("server");
  });

  it("keeps per-field messages from a validation failure", () => {
    const error = classify(400, {
      code: "VALIDATION_FAILED",
      fieldErrors: { passengerName: "must not be blank", seats: "must be less than or equal to 9" },
    });
    expect(error.kind).toBe("validation");
    expect(error.fieldErrors.seats).toBe("must be less than or equal to 9");
    expect(error.message).toBe("Some fields were refused.");
  });

  it("reads Retry-After on a 503", () => {
    const error = classify(503, { code: "LOCK_TIMEOUT", message: "busy" }, "1");
    expect(error.kind).toBe("unavailable");
    expect(error.retryAfter).toBe(1);
    expect(hint(error)).toContain("1 s");
    expect(classify(503, null, "Wed, 21 Oct 2026 07:28:00 GMT").retryAfter).toBeNull();
  });

  it("marks the console's own errors, and a body with no envelope", () => {
    expect(classify(502, { code: "CONSOLE_UPSTREAM_UNREACHABLE", message: "down" }).kind).toBe("console");
    expect(classify(404, "").code).toBe("HTTP_404");
    expect(classify(404, null).kind).toBe("notFound");
    expect(classify(0, null).kind).toBe("network");
  });
});
