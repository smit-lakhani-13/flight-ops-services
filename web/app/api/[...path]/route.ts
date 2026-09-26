import { forward } from "@/lib/proxy";

// Every /api/* call from the console's pages lands here and is forwarded to
// the API by lib/proxy.ts#forward. PUT is exported only so that forward
// refuses it in the envelope, with the path's own Allow header; the API has
// no PUT. OPTIONS is answered by Next itself, with no CORS headers.

interface Context {
  params: Promise<{ path: string[] }>;
}

async function handle(request: Request, context: Context): Promise<Response> {
  const { path } = await context.params;
  return forward(request, path);
}

export const GET = handle;
export const HEAD = handle;
export const POST = handle;
export const PUT = handle;
export const PATCH = handle;
export const DELETE = handle;
