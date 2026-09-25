// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { PlusIcon } from "./icons";
import { Button, countFrom, EmptyState, Field, Select, statusClass, TextInput } from "./ui";

afterEach(cleanup);

describe("Button", () => {
  it("is a plain button unless told otherwise, in the tone it is given", () => {
    render(
      <>
        <Button>Primary</Button>
        <Button tone="secondary">Secondary</Button>
        <Button tone="danger">Danger</Button>
        <Button tone="ghost">Ghost</Button>
        <Button type="submit">Submit</Button>
      </>,
    );
    expect(screen.getByRole("button", { name: "Primary" }).getAttribute("type")).toBe("button");
    expect(screen.getByRole("button", { name: "Submit" }).getAttribute("type")).toBe("submit");
    expect(screen.getByRole("button", { name: "Primary" }).className).toContain("bg-accent-strong");
    expect(screen.getByRole("button", { name: "Secondary" }).className).toContain("border-slate-300");
    expect(screen.getByRole("button", { name: "Danger" }).className).toContain("bg-rose-700");
    expect(screen.getByRole("button", { name: "Ghost" }).className).not.toContain("border");
  });

  it("gives every tone the focus outline and the touch height, and no outline-none", () => {
    render(<Button tone="ghost">Ghost</Button>);
    const classes = screen.getByRole("button").className;
    expect(classes).toContain("focus-visible:outline-2");
    expect(classes).toContain("max-xl:min-h-11");
    expect(classes).not.toContain("outline-none");
  });

  it("while busy, is disabled, says so, and swaps its icon for the ring with the same name", () => {
    const onClick = vi.fn();
    const { rerender } = render(
      <Button icon={<PlusIcon />} onClick={onClick}>
        Create flight
      </Button>,
    );
    const idle = screen.getByRole("button", { name: "Create flight" });
    const idleIcon = idle.querySelector("svg")?.innerHTML;
    expect(idle.hasAttribute("aria-busy")).toBe(false);

    rerender(
      <Button icon={<PlusIcon />} onClick={onClick} busy>
        Create flight
      </Button>,
    );
    const busy = screen.getByRole("button", { name: "Create flight" });
    expect(busy).toHaveProperty("disabled", true);
    expect(busy.getAttribute("aria-busy")).toBe("true");
    expect(busy.querySelectorAll("svg")).toHaveLength(1);
    expect(busy.querySelector("svg")?.innerHTML).not.toBe(idleIcon);
    fireEvent.click(busy);
    expect(onClick).not.toHaveBeenCalled();
  });
});

describe("Field", () => {
  it("names its input and describes it with the hint", () => {
    render(
      <Field label="Seats" hint="1 to 9">
        <TextInput />
      </Field>,
    );
    const input = screen.getByLabelText("Seats");
    const hint = screen.getByText("1 to 9");
    expect(input.getAttribute("aria-describedby")).toBe(hint.id);
    expect(input.hasAttribute("aria-invalid")).toBe(false);
  });

  it("replaces the hint with the error, marks the input invalid and announces it", () => {
    render(
      <Field label="Seats" hint="1 to 9" error="must be at most 9">
        <TextInput />
      </Field>,
    );
    const input = screen.getByLabelText("Seats");
    const error = screen.getByRole("alert");
    expect(error.textContent).toBe("must be at most 9");
    expect(input.getAttribute("aria-describedby")).toBe(error.id);
    expect(input.getAttribute("aria-invalid")).toBe("true");
    expect(screen.queryByText("1 to 9")).toBeNull();
  });

  it("keeps a description the page passes in, and wires a select the same way", () => {
    render(
      <>
        <p id="extra">Sorted by the service</p>
        <Field label="Order" hint="Five choices">
          <Select aria-describedby="extra">
            <option>First</option>
          </Select>
        </Field>
      </>,
    );
    const select = screen.getByLabelText("Order");
    const hint = screen.getByText("Five choices");
    expect(select.getAttribute("aria-describedby")).toBe(`extra ${hint.id}`);
  });

  it("gives inputs 16 px text and the touch height below 1280 px", () => {
    render(
      <Field label="Origin">
        <TextInput />
      </Field>,
    );
    const classes = screen.getByLabelText("Origin").className;
    expect(classes).toContain("max-xl:text-base");
    expect(classes).toContain("max-xl:min-h-11");
  });
});

describe("EmptyState", () => {
  it("shows its icon, its one line and its action", () => {
    render(<EmptyState icon={<PlusIcon />} action={<Button>New flight</Button>}>No flights match this search.</EmptyState>);
    expect(screen.getByText("No flights match this search.")).toBeTruthy();
    expect(screen.getByRole("button", { name: "New flight" })).toBeTruthy();
    expect(document.querySelector("svg")?.getAttribute("aria-hidden")).toBe("true");
  });

  it("needs no action", () => {
    render(<EmptyState icon={<PlusIcon />}>No requests yet.</EmptyState>);
    expect(screen.queryByRole("button")).toBeNull();
  });
});

describe("statusClass", () => {
  it("gives 409 its own class and everything else its hundred", () => {
    expect([201, 204, 302, 400, 404, 409, 413, 500, 503, 0].map(statusClass)).toEqual([
      "2xx",
      "2xx",
      "3xx",
      "4xx",
      "4xx",
      "409",
      "4xx",
      "5xx",
      "5xx",
      "none",
    ]);
  });
});

describe("countFrom", () => {
  it("reads a number only when the whole text is one", () => {
    expect(countFrom(" 3 ")).toBe(3);
    expect(countFrom("1.5")).toBe(1.5);
    expect(countFrom("-2")).toBe(-2);
    expect(countFrom("2x")).toBeNaN();
    expect(countFrom("")).toBeNaN();
    expect(JSON.stringify({ seats: countFrom("two") })).toBe('{"seats":null}');
  });
});
