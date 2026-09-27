import { describe, expect, it } from "vitest";
import { forTemplate, LONGEST_BOOKING_ID, segment, shortened } from "./titles";

describe("shortened", () => {
  it("leaves a value of sixteen characters or fewer alone", () => {
    expect(shortened("UA123")).toBe("UA123");
    expect(shortened("A".repeat(16))).toBe("A".repeat(16));
  });

  it("cuts a longer value to sixteen characters and an ellipsis", () => {
    expect(shortened("B".repeat(4000))).toBe(`${"B".repeat(16)}…`);
  });

  it("never splits an emoji at the cut", () => {
    const cut = shortened("A".repeat(15) + "\u{1F600}\u{1F600}");
    expect(cut).toBe(`${"A".repeat(15)}\u{1F600}…`);
    expect(cut.isWellFormed()).toBe(true);
  });

  it("keeps every booking id a Java long can hold", () => {
    expect(shortened("9".repeat(19), LONGEST_BOOKING_ID)).toBe("9".repeat(19));
    expect(shortened("9".repeat(4000), LONGEST_BOOKING_ID)).toBe(`${"9".repeat(20)}…`);
  });
});

describe("segment", () => {
  it("decodes the address segment", () => {
    expect(segment("UA123")).toBe("UA123");
    expect(segment("a%20b")).toBe("a b");
    expect(segment("%E6%97%A5%E6%9C%AC123")).toBe("日本123");
  });

  it("keeps a malformed escape as it came", () => {
    expect(segment("%E0%A4%A")).toBe("%E0%A4%A");
  });
});

describe("forTemplate", () => {
  it("survives Next's template, which reads a dollar as a pattern", () => {
    const template = "%s · flight-ops console";
    for (const title of ["$'", "UA$$1", "$&", "UA123"]) {
      expect(template.replace(/%s/g, forTemplate(title))).toBe(`${title} · flight-ops console`);
    }
  });
});
