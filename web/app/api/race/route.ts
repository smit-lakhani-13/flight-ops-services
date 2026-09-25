import { runRace } from "@/lib/race";

// Ten concurrent bookings on one key, fired from this server. See
// lib/race.ts#runRace for why the page cannot do it itself.
export async function POST(request: Request): Promise<Response> {
  return runRace(request);
}
