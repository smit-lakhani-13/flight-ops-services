import { describe, expect, it } from "vitest";
import { basicAuthorization, encodeUtf8Base64 } from "./base64";

describe("basicAuthorization", () => {
  it("encodes the development credentials as curl -u does", () => {
    expect(basicAuthorization("api", "dev-secret")).toBe("Basic YXBpOmRldi1zZWNyZXQ=");
  });

  it("encodes UTF-8, which btoa alone cannot", () => {
    expect(encodeUtf8Base64("é")).toBe("w6k=");
    expect(basicAuthorization("ops", "pässwörd")).toBe(`Basic ${Buffer.from("ops:pässwörd", "utf8").toString("base64")}`);
  });

  it("refuses an empty user name or one with a colon", () => {
    expect(() => basicAuthorization("", "x")).toThrow();
    expect(() => basicAuthorization("a:b", "x")).toThrow(/colon/);
  });

  it("allows a colon in the password", () => {
    expect(basicAuthorization("api", "a:b")).toBe(`Basic ${Buffer.from("api:a:b").toString("base64")}`);
  });
});
