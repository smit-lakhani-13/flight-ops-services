import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { isBookable, isCancellable, nextStates } from "./transitions";
import { FLIGHT_STATUSES } from "./types";

// A checked copy. lib/transitions.ts repeats two switches from the service's
// enum to decide which buttons to offer, and this test reads the enum itself,
// so a change on the Java side fails here instead of leaving a stale button.
// The parser knows only the shapes the file uses today; anything else fails
// loudly rather than passing by accident.
const ENUM = new URL("../../src/main/java/com/smit/flightops/entity/FlightStatus.java", import.meta.url);
const source = readFileSync(fileURLToPath(ENUM), "utf8")
  .replace(/\/\*[\s\S]*?\*\//g, "")
  .replace(/\/\/[^\n]*/g, "");

function constants(): string[] {
  const body = /public enum FlightStatus \{([^;]*);/.exec(source);
  if (!body?.[1]) throw new Error("FlightStatus.java: no enum constant list");
  return body[1].split(",").map((c) => c.trim());
}

/** Each constant in `method`'s `switch (this)`, mapped to the text after its arrow. */
function arms(method: string): Map<string, string> {
  const start = source.indexOf(`public boolean ${method}(`);
  const open = source.indexOf("switch (this) {", start);
  const close = source.indexOf("};", open);
  if (start < 0 || open < 0 || close < 0) throw new Error(`FlightStatus.java: no switch in ${method}`);
  const result = new Map<string, string>();
  for (const arm of source.slice(open + "switch (this) {".length, close).split(";")) {
    if (arm.trim() === "") continue;
    const match = /^\s*case\s+([A-Z_,\s]+?)\s*->\s*([\s\S]+)$/.exec(arm);
    if (!match?.[1] || !match[2]) throw new Error(`FlightStatus.java: unreadable arm in ${method}: ${arm.trim()}`);
    for (const constant of match[1].split(",")) {
      result.set(constant.trim(), match[2].replace(/\s+/g, " ").trim());
    }
  }
  return result;
}

/** `next == A || next == B` as [A, B]; `false` as []. */
function targets(expression: string): string[] {
  if (expression === "false") return [];
  return expression.split("||").map((term) => {
    const match = /^next == ([A-Z_]+)$/.exec(term.trim());
    if (!match?.[1]) throw new Error(`FlightStatus.java: unreadable condition: ${term.trim()}`);
    return match[1];
  });
}

const sorted = (values: readonly string[]) => [...values].sort();

describe("transitions, checked against FlightStatus.java", () => {
  it("knows the same six statuses", () => {
    expect(sorted(FLIGHT_STATUSES)).toEqual(sorted(constants()));
  });

  it("offers exactly the moves canTransitionTo allows, and never the current status", () => {
    const service = arms("canTransitionTo");
    for (const status of FLIGHT_STATUSES) {
      const expression = service.get(status);
      expect(expression, status).toBeDefined();
      expect(sorted(nextStates(status)), status).toEqual(sorted(targets(expression!)));
      expect(nextStates(status), status).not.toContain(status);
    }
  });

  it("sells seats where isBookable says so", () => {
    const service = arms("isBookable");
    for (const status of FLIGHT_STATUSES) {
      expect(isBookable(status), status).toBe(service.get(status) === "true");
    }
  });

  it("offers Cancel where a move to CANCELLED is allowed, as Flight#cancel needs", () => {
    // Flight#cancel is updateStatus(CANCELLED); a status may always become
    // itself, so cancelling a cancelled flight answers 204 and changes nothing.
    const service = arms("canTransitionTo");
    for (const status of FLIGHT_STATUSES) {
      const allowed = status === "CANCELLED" || targets(service.get(status) ?? "false").includes("CANCELLED");
      expect(isCancellable(status), status).toBe(allowed);
    }
  });
});
