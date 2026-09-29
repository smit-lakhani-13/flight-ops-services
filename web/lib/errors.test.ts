import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
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

  it("gives one hint for a 503 that fits a held row and a database that is down", () => {
    // The API's message says which it was; the hint must not say it for it.
    for (const code of ["LOCK_TIMEOUT", "DATABASE_UNAVAILABLE"]) {
      expect(hint(classify(503, { code, message: "m" }, "1")), code).toBe(
        "The service could not finish the request just now. Retry after 1 s.",
      );
    }
    expect(hint(classify(503, { code: "DATABASE_UNAVAILABLE", message: "m" }))).toBe("The service is unavailable. Retry shortly.");
  });

  it("marks the console's own errors, and a body with no envelope", () => {
    expect(classify(502, { code: "CONSOLE_UPSTREAM_UNREACHABLE", message: "down" }).kind).toBe("console");
    expect(classify(404, "").code).toBe("HTTP_404");
    expect(classify(404, null).kind).toBe("notFound");
    expect(classify(0, null).kind).toBe("network");
  });

  it("warns that a timed-out change may still be applied", () => {
    const timeout = classify(504, { code: "CONSOLE_UPSTREAM_TIMEOUT", message: "The API did not answer within 15 s." });
    expect(timeout.kind).toBe("console");
    expect(hint(timeout)).toContain("may still finish the request");
    const unreachable = classify(502, { code: "CONSOLE_UPSTREAM_UNREACHABLE", message: "down" });
    expect(hint(unreachable)).not.toContain("may still finish");
  });

  it("says what to do after a refused credential, a missing authority and no answer at all", () => {
    expect(hint(classify(401, { code: "UNAUTHENTICATED", message: "no" }))).toBe("The credentials were not accepted. Sign in again.");
    expect(hint(classify(403, { code: "FORBIDDEN", message: "no" }))).toContain("lacks the authority for this call");
    expect(hint(classify(0, null))).toBe("Check that the console's server is still running.");
  });

  // Read from the sources, as transitions.test.ts reads FlightStatus.java: a
  // code the console's server starts to send without a place in CONSOLE_CODES
  // would be shown as the API's own error, and this fails first.
  it("sorts every code the console's own server sends as the console's, with a hint", () => {
    const sources = ["proxy.ts", "race.ts"]
      .map((name) => readFileSync(fileURLToPath(new URL(`./${name}`, import.meta.url)), "utf8"))
      .join("\n");
    const codes = [...new Set(sources.match(/\bCONSOLE_[A-Z_]+\b/g))];
    expect(codes).toHaveLength(9);
    for (const code of codes) {
      const error = classify(502, { code, message: "m" });
      expect(error.kind, code).toBe("console");
      expect(hint(error), code).toBeTruthy();
    }
  });
});
