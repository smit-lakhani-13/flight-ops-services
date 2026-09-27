// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { useState } from "react";
import { afterEach, describe, expect, it } from "vitest";
import { Pager } from "./Pager";

afterEach(cleanup);

/** A list of `pages` pages whose next page arrives when `arrive` is called. */
function List({ pages, onAsk }: { pages: number; onAsk: (arrive: () => void) => void }) {
  const [number, setNumber] = useState(0);
  return (
    <Pager
      page={{ size: 10, number, totalElements: pages * 10, totalPages: pages }}
      onPage={(next) => onAsk(() => setNumber(next))}
    />
  );
}

function button(name: string) {
  return screen.getByRole<HTMLButtonElement>("button", { name });
}

/** Presses a button from the keyboard's point of view: focused, then clicked. */
async function press(name: string) {
  button(name).focus();
  await act(async () => fireEvent.click(button(name)));
}

describe("Pager", () => {
  it("hands the focus across when a press reaches the first or the last page", async () => {
    render(<List pages={3} onAsk={(arrive) => arrive()} />);

    await press("Next");
    expect(screen.getByText(/page 2 of 3/)).not.toBeNull();
    expect(document.activeElement).toBe(button("Next"));

    await press("Next");
    expect(button("Next").disabled).toBe(true);
    expect(document.activeElement).toBe(button("Previous"));

    await press("Previous");
    await press("Previous");
    expect(button("Previous").disabled).toBe(true);
    expect(document.activeElement).toBe(button("Next"));
  });

  it("leaves the focus alone if it went elsewhere while the page loaded", async () => {
    let arrive = () => {};
    render(
      <>
        <input aria-label="Search" />
        <List pages={2} onAsk={(next) => (arrive = next)} />
      </>,
    );

    button("Next").focus();
    fireEvent.click(button("Next"));
    const search = screen.getByLabelText("Search");
    search.focus();
    await act(async () => arrive());

    expect(button("Next").disabled).toBe(true);
    expect(document.activeElement).toBe(search);
  });
});
