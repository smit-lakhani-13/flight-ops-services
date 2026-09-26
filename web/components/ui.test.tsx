// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { PlusIcon } from "./icons";
import {
  Button,
  countFrom,
  EmptyState,
  Field,
  formatInstant,
  NONE,
  PageTitle,
  pageFor,
  SeatBar,
  Select,
  statusClass,
  TextInput,
  TextLink,
} from "./ui";

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
    expect(screen.getByRole("button", { name: "Ghost" }).className).toContain("border-transparent");
  });

  it("gives every tone a border, the focus outline and the touch height, and no outline-none", () => {
    for (const tone of ["primary", "secondary", "danger", "ghost"] as const) {
      render(<Button tone={tone}>{tone}</Button>);
      const classes = screen.getByRole("button", { name: tone }).className.split(/\s+/);
      expect(classes, tone).toContain("border");
      expect(classes.some((name) => /^border-(transparent|slate-\d+)$/.test(name)), tone).toBe(true);
      expect(classes, tone).toContain("focus-visible:outline-2");
      expect(classes, tone).toContain("max-xl:min-h-11");
      expect(classes, tone).toContain("pointer-coarse:min-h-11");
      expect(classes, tone).not.toContain("outline-none");
    }
  });

  it("while busy, ignores presses, says so, keeps the focus, and swaps its icon for the ring", () => {
    const onClick = vi.fn();
    const { rerender } = render(
      <Button icon={<PlusIcon />} onClick={onClick}>
        Create flight
      </Button>,
    );
    const idle = screen.getByRole("button", { name: "Create flight" });
    const idleIcon = idle.querySelector("svg")?.innerHTML;
    expect(idle.hasAttribute("aria-busy")).toBe(false);
    expect(idle.hasAttribute("aria-disabled")).toBe(false);
    idle.focus();

    rerender(
      <Button icon={<PlusIcon />} onClick={onClick} busy>
        Create flight
      </Button>,
    );
    const busy = screen.getByRole("button", { name: "Create flight" });
    expect(busy).toBe(idle);
    expect(busy).toHaveProperty("disabled", false);
    expect(busy.getAttribute("aria-disabled")).toBe("true");
    expect(busy.getAttribute("aria-busy")).toBe("true");
    expect(document.activeElement).toBe(busy);
    expect(busy.querySelectorAll("svg")).toHaveLength(1);
    expect(busy.querySelector("svg")?.innerHTML).not.toBe(idleIcon);
    fireEvent.click(busy);
    expect(onClick).not.toHaveBeenCalled();
  });

  it("while busy, does not submit its form, and is disabled only when told and not busy", () => {
    const onSubmit = vi.fn((event: { preventDefault: () => void }) => event.preventDefault());
    const { rerender } = render(
      <form onSubmit={onSubmit}>
        <Button type="submit" busy>
          Book
        </Button>
      </form>,
    );
    fireEvent.click(screen.getByRole("button", { name: "Book" }));
    expect(onSubmit).not.toHaveBeenCalled();

    rerender(
      <form onSubmit={onSubmit}>
        <Button type="submit" disabled>
          Book
        </Button>
      </form>,
    );
    expect(screen.getByRole("button", { name: "Book" })).toHaveProperty("disabled", true);

    rerender(
      <form onSubmit={onSubmit}>
        <Button type="submit">Book</Button>
      </form>,
    );
    fireEvent.click(screen.getByRole("button", { name: "Book" }));
    expect(onSubmit).toHaveBeenCalledTimes(1);
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

  it("replaces the hint with the error and marks the input invalid, without a second alert", () => {
    render(
      <Field label="Seats" hint="1 to 9" error="must be at most 9">
        <TextInput />
      </Field>,
    );
    const input = screen.getByLabelText("Seats");
    const error = screen.getByText("must be at most 9");
    expect(screen.queryByRole("alert")).toBeNull();
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
    expect(classes).toContain("pointer-coarse:text-base");
    expect(classes).toContain("border-slate-500");
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

describe("SeatBar", () => {
  const bar = (container: HTMLElement) => container.querySelector<HTMLElement>("[data-testid=seat-bar]");
  const width = (container: HTMLElement) => (bar(container)?.firstElementChild as HTMLElement).style.width;

  it("reads out the seats left and fills the bar with them", () => {
    const { container } = render(<SeatBar available={12} total={20} />);
    expect(container.textContent).toBe("12/20 seats left");
    expect(width(container)).toBe("60%");
    expect(bar(container)?.getAttribute("aria-hidden")).toBe("true");
    expect(bar(container)?.firstElementChild?.className).toContain("bg-accent");
  });

  it("turns amber at a tenth left, and keeps the bar inside its track", () => {
    const low = render(<SeatBar available={2} total={20} />);
    expect(width(low.container)).toBe("10%");
    expect(bar(low.container)?.firstElementChild?.className).toContain("bg-amber-500");
    cleanup();

    expect(width(render(<SeatBar available={-3} total={20} />).container)).toBe("0%");
    cleanup();
    expect(width(render(<SeatBar available={25} total={20} />).container)).toBe("100%");
    cleanup();
    expect(width(render(<SeatBar available={0} total={0} />).container)).toBe("0%");
  });

  it("leaves the bar out on a phone when compact", () => {
    const { container } = render(<SeatBar available={5} total={10} compact />);
    expect(bar(container)?.className).toContain("hidden sm:block");
  });
});

describe("PageTitle", () => {
  it("names the tab from documentTitle, or from a plain-text title", () => {
    const { rerender } = render(<PageTitle title={<span>UA123</span>} documentTitle="UA123" />);
    expect(document.title).toBe("UA123 · flight-ops console");
    expect(screen.getByRole("heading", { level: 1 }).textContent).toBe("UA123");

    rerender(<PageTitle title="Flights" />);
    expect(document.title).toBe("Flights · flight-ops console");
  });
});

describe("TextLink", () => {
  it("gets the touch height only when it stands on a line of its own", () => {
    render(
      <>
        <TextLink href="/flights">In a sentence</TextLink>
        <TextLink href="/flights" standalone>
          All flights
        </TextLink>
      </>,
    );
    expect(screen.getByRole("link", { name: "In a sentence" }).className).not.toContain("min-h-11");
    expect(screen.getByRole("link", { name: "All flights" }).className).toContain("max-xl:min-h-11");
  });
});

describe("pageFor", () => {
  it("turns a Location header for a flight or a booking into the console page", () => {
    expect(pageFor("/api/v1/flights/UA999")).toBe("/flights/UA999");
    expect(pageFor("/api/v1/bookings/42")).toBe("/bookings/42");
  });

  it("refuses anything else, so the console only ever moves to its own pages", () => {
    for (const location of [
      null,
      "",
      "/api/v1/passengers/1",
      "/api/v1/flights/",
      "/api/v1/flights/../ops",
      "/api/v1/flights/UA1/status",
      "https://example.com/api/v1/flights/UA1",
      "//example.com/api/v1/flights/UA1",
    ]) {
      expect(pageFor(location)).toBeNull();
    }
  });
});

describe("formatInstant", () => {
  it("shows an absent value as the dash and a value it cannot read as it came", () => {
    expect(formatInstant(null)).toBe(NONE);
    expect(formatInstant(undefined)).toBe(NONE);
    expect(formatInstant("not a date")).toBe("not a date");
  });

  it("formats an instant with its year and a zone", () => {
    const text = formatInstant("2026-09-26T10:00:00Z");
    expect(text).toContain("2026");
    expect(text).not.toContain("T10:00:00Z");
  });
});
