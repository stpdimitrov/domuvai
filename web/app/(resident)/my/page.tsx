import { connection } from "next/server";
import { LOGOUT } from "@/lib/auth/gate";
import { currentSession } from "@/lib/auth/server";

/**
 * Rule: PM-SYS-015 — the resident home: who is signed in, and the way out. Nothing else yet — the screens with data
 * come in their own slices, each under the same budget. Sign-out is a form posted to this server, as in the console.
 */
export default async function ResidentHome() {
  await connection();                          // per request: who is signed in is the request's, not the build's
  const session = await currentSession();
  return (
    <>
      <h1>Начало</h1>
      {session ? (
        <form method="post" action={LOGOUT}>
          <p>Влезли сте като {session.name ?? "потребител без име"}.</p>
          <button type="submit">Изход</button>
        </form>
      ) : (
        <p>Тук не се влиза с профил.</p>
      )}
    </>
  );
}
