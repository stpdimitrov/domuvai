import { api } from "@/lib/api/client";
import { LOGOUT } from "@/lib/auth/gate";
import { currentSession } from "@/lib/auth/server";
import { reach } from "@/lib/console";

/**
 * Who is signed in, and the way out (ADR-011, AUTH-03). The name is what the issuer calls the person, for display
 * only; who they are to the system is the api's answer to this request's own token (`GET /api/identity/me`): the
 * login it read from the token, and the registered party that login is tied to — or that it is tied to none.
 * Sign-out is a form posted to this server — no script, and nothing another site can trigger. Where people do not
 * sign in, there is nobody to show.
 */
export default async function SignedIn() {
  const session = await currentSession();
  if (!session) return null;
  const me = (await reach(() => api.GET("/api/identity/me")))?.data;
  return (
    <form method="post" action={LOGOUT} className="signed-in" data-login={me?.subject}>
      <span title={me?.subject}>{session.name ?? "Влязъл потребител"}</span>
      {!me ? (
        <span style={{ color: "#8E2318" }}>системата не разпозна входа</span>
      ) : me.partyId ? null : (
        <span>без лице в книгата</span>
      )}
      <button type="submit">Изход</button>
    </form>
  );
}
