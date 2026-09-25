// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { classify } from "@/lib/errors";
import { TransitionControl } from "./TransitionControl";

afterEach(cleanup);

describe("TransitionControl", () => {
  it("offers only the moves the service allows", () => {
    render(<TransitionControl status="DEPARTED" onSend={async () => null} />);
    const group = screen.getByRole("group", { name: "Allowed transitions" });
    const labels = Array.from(group.querySelectorAll("button"), (b) => b.textContent);
    expect(labels).toEqual(["Move to ARRIVED"]);
  });

  it("says a terminal status has no next move", () => {
    render(<TransitionControl status="CANCELLED" onSend={async () => null} />);
    expect(screen.getByRole("group", { name: "Allowed transitions" }).textContent).toContain("terminal");
  });

  it("sends a refused status anyway and shows the service's 409", async () => {
    const onSend = vi.fn(async () => classify(409, { code: "ILLEGAL_STATUS_TRANSITION", message: "SCHEDULED cannot become ARRIVED" }));
    render(<TransitionControl status="SCHEDULED" onSend={onSend} />);

    fireEvent.change(screen.getByLabelText("Send any status:"), { target: { value: "ARRIVED" } });
    fireEvent.click(screen.getByRole("button", { name: "Send" }));

    await waitFor(() => expect(screen.getByRole("alert").dataset.code).toBe("ILLEGAL_STATUS_TRANSITION"));
    expect(onSend).toHaveBeenCalledWith("ARRIVED");
  });

  it("clears the error after a move the service accepts", async () => {
    const onSend = vi
      .fn<(next: string) => Promise<ReturnType<typeof classify> | null>>()
      .mockResolvedValueOnce(classify(409, { code: "ILLEGAL_STATUS_TRANSITION", message: "no" }))
      .mockResolvedValueOnce(null);
    render(<TransitionControl status="SCHEDULED" onSend={onSend} />);

    fireEvent.click(screen.getByRole("button", { name: "Send" }));
    await screen.findByRole("alert");
    fireEvent.click(screen.getByRole("button", { name: "Move to BOARDING" }));

    await waitFor(() => expect(screen.queryByRole("alert")).toBeNull());
    expect(onSend).toHaveBeenLastCalledWith("BOARDING");
  });
});
