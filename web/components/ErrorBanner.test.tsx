// @vitest-environment jsdom
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { classify } from "@/lib/errors";
import { ErrorBanner } from "./ErrorBanner";

afterEach(cleanup);

describe("ErrorBanner", () => {
  it("renders nothing without an error", () => {
    const { container } = render(<ErrorBanner error={null} />);
    expect(container.innerHTML).toBe("");
  });

  it("shows the API's code, message and status, and a hint", () => {
    render(<ErrorBanner error={classify(503, { code: "LOCK_TIMEOUT", message: "The flight is busy" }, "1")} />);
    const banner = screen.getByRole("alert");
    expect(banner.dataset.code).toBe("LOCK_TIMEOUT");
    expect(banner.textContent).toContain("503");
    expect(banner.textContent).toContain("The flight is busy");
    expect(banner.textContent).toContain("1 s");
  });

  it("lists only the field messages no input claimed", () => {
    const error = classify(400, {
      code: "VALIDATION_FAILED",
      fieldErrors: { passengerName: "must not be blank", seats: "must be at most 9" },
    });
    render(<ErrorBanner error={error} claimedFields={["seats"]} />);
    const banner = screen.getByRole("alert");
    expect(banner.textContent).toContain("passengerName: must not be blank");
    expect(banner.textContent).not.toContain("must be at most 9");
  });

  it("leaves out the status on a network failure", () => {
    render(<ErrorBanner error={classify(0, null)} />);
    const banner = screen.getByRole("alert");
    expect(banner.dataset.code).toBe("HTTP_0");
    expect(banner.textContent?.startsWith("0")).toBe(false);
  });
});
