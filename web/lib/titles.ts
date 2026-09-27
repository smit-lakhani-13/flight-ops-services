// How a page names the flight or booking in its address, in its heading and
// its tab. Plain code with no client hooks, so the server layout that titles
// the tab and the page itself name it the same way.

// A real flight number is ten characters at most, and a booking id is a Java
// long, at most nineteen digits and a sign. The cut leaves room above both:
// past sixteen characters, or twenty for an id, which only a mistyped address
// makes, the name is cut short so it cannot fill the heading, the tab or a
// screen reader with thousands of characters.
export const LONGEST_FLIGHT_NUMBER = 16;
export const LONGEST_BOOKING_ID = 20;

/** Cuts by code points, so an emoji at the cut is never split in half. */
export function shortened(value: string, longest = LONGEST_FLIGHT_NUMBER): string {
  const chars = Array.from(value);
  return chars.length > longest ? `${chars.slice(0, longest).join("")}…` : value;
}

/**
 * The address segment as text. The server's params arrive decoded but the
 * client's useParams value does not, so each page decodes it once, and keeps
 * a malformed escape as it came.
 */
export function segment(value: string): string {
  try {
    return decodeURIComponent(value);
  } catch {
    return value;
  }
}

/**
 * Next puts a page's title into the root template with String.replace, which
 * reads a "$" as a pattern, so a title taken from the address escapes it.
 */
export function forTemplate(value: string): string {
  return value.replaceAll("$", "$$$$");
}
