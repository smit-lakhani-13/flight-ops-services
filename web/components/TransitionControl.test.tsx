// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { useState } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { classify } from "@/lib/errors";
import type { FlightStatus } from "@/lib/types";
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
    let answer = () => {};
    const refusal = classify(409, { code: "ILLEGAL_STATUS_TRANSITION", message: "SCHEDULED cannot become ARRIVED" });
    const onSend = vi.fn(() => new Promise<typeof refusal>((resolve) => (answer = () => resolve(refusal))));
    render(<TransitionControl status="SCHEDULED" onSend={onSend} />);

    fireEvent.change(screen.getByLabelText("Send any status:"), { target: { value: "ARRIVED" } });
    const send = screen.getByRole<HTMLButtonElement>("button", { name: "Send" });
    send.focus();
    fireEvent.click(send);
    // Busy rather than disabled, so the focus stays on it while it waits.
    expect(send.getAttribute("aria-busy")).toBe("true");
    expect(send.disabled).toBe(false);

    await act(async () => answer());
    expect(screen.getByRole("alert").dataset.code).toBe("ILLEGAL_STATUS_TRANSITION");
    expect(onSend).toHaveBeenCalledWith("ARRIVED");
    expect(document.activeElement).toBe(send);
  });

  it("keeps the focus on a busy move, then hands it to the moves that follow", async () => {
    let answer = () => {};
    // The flight page in miniature: an accepted move changes the status.
    function Flight() {
      const [status, setStatus] = useState<FlightStatus>("SCHEDULED");
      return (
        <TransitionControl
          status={status}
          onSend={(next) =>
            new Promise((resolve) => {
              answer = () => {
                setStatus(next);
                resolve(null);
              };
            })
          }
        />
      );
    }
    render(<Flight />);
    const move = screen.getByRole<HTMLButtonElement>("button", { name: "Move to BOARDING" });
    move.focus();
    fireEvent.click(move);

    expect(move.getAttribute("aria-busy")).toBe("true");
    expect(move.disabled).toBe(false);
    expect(document.activeElement).toBe(move);
    expect(screen.getByRole<HTMLButtonElement>("button", { name: "Move to DELAYED" }).disabled).toBe(true);
    expect(screen.getByRole<HTMLButtonElement>("button", { name: "Send" }).disabled).toBe(true);

    await act(async () => answer());
    expect(screen.queryByRole("button", { name: "Move to BOARDING" })).toBeNull();
    expect(document.activeElement).toBe(screen.getByRole("group", { name: "Allowed transitions" }));
    expect(screen.getByRole<HTMLButtonElement>("button", { name: "Move to DEPARTED" }).disabled).toBe(false);
  });

  it("drops a refusal once the status moves some other way", async () => {
    const onSend = async () => classify(409, { code: "ILLEGAL_STATUS_TRANSITION", message: "no" });
    const { rerender } = render(<TransitionControl status="SCHEDULED" onSend={onSend} />);

    fireEvent.click(screen.getByRole("button", { name: "Send" }));
    await screen.findByRole("alert");
    rerender(<TransitionControl status="CANCELLED" onSend={onSend} />);

    expect(screen.queryByRole("alert")).toBeNull();
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

  it("gives the buttons back and logs it when a send throws", async () => {
    const logged = vi.spyOn(console, "error").mockImplementation(() => {});
    const failure = new Error("boom");
    render(<TransitionControl status="SCHEDULED" onSend={() => Promise.reject(failure)} />);
    const move = screen.getByRole<HTMLButtonElement>("button", { name: "Move to BOARDING" });

    fireEvent.click(move);

    await waitFor(() => expect(logged).toHaveBeenCalledWith("A status change failed", failure));
    await waitFor(() => expect(move.disabled).toBe(false));
    expect(screen.getByRole<HTMLButtonElement>("button", { name: "Send" }).disabled).toBe(false);
    logged.mockRestore();
  });
});
