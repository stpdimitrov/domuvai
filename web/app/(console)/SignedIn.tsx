import { LOGOUT } from "@/lib/auth/gate";
import { currentSession } from "@/lib/auth/server";

/**
 * Who is signed in, and the way out (ADR-011, AUTH-03). The name is what the issuer calls the person, for display
 * only. Sign-out is a form posted to this server — no script, and nothing another site can trigger. Where people do
 * not sign in, there is nobody to show.
 */
export default async function SignedIn() {
  const session = await currentSession();
  if (!session) return null;
  return (
    <form method="post" action={LOGOUT} className="signed-in">
      <span>{session.name ?? "Влязъл потребител"}</span>
      <button type="submit">Изход</button>
    </form>
  );
}
