// HTTP Basic credentials, encoded the way Spring Security decodes them: UTF-8
// bytes, then base64. btoa() alone throws on anything outside Latin-1.

export function encodeUtf8Base64(text: string): string {
  const bytes = new TextEncoder().encode(text);
  let binary = "";
  for (const byte of bytes) {
    binary += String.fromCharCode(byte);
  }
  return btoa(binary);
}

/**
 * The `Authorization` value for a user and password. RFC 7617 forbids a colon
 * in the user name, because the first colon separates the two.
 */
export function basicAuthorization(user: string, password: string): string {
  if (user.length === 0) {
    throw new Error("The user name is empty.");
  }
  if (user.includes(":")) {
    throw new Error("A user name cannot contain a colon.");
  }
  return `Basic ${encodeUtf8Base64(`${user}:${password}`)}`;
}
