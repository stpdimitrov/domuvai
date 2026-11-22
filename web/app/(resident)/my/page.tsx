import { connection } from "next/server";
import { LOGOUT } from "@/lib/auth/gate";
import { currentSession } from "@/lib/auth/server";
import { api } from "@/lib/api/client";
import { blocks, owes } from "@/lib/mine";

/**
 * Rule: PM-SEC-002, PM-SYS-015 — Начало: the units the api says this person holds, nothing more. An OWN block shows
 * the balance of its statement and links to it; a USR block shows no figure. The api decides every read. Server-rendered,
 * no client script, so the route stays under the payload budget.
 */
// Rule: PM-SEC-002, PM-SYS-015
export default async function ResidentHome() {
  await connection();                          // per request: who is signed in is the request's, not the build's
  const session = await currentSession();
  const mine = session ? await api.GET("/api/registry/me/units", {}).catch(() => null) : null;
  const list = blocks(mine?.data ?? []);
  const balances = await Promise.all(list.map(async (b) => {
    if (!b.statementHref) return null;
    const r = await api.GET("/api/money/units/{unitId}/statement", { params: { path: { unitId: b.unitId } } }).catch(() => null);
    return r?.data ? owes(r.data.balanceMinor) : null;
  }));
  return (
    <>
      <h1>Начало</h1>
      {!session ? <p>Тук не се влиза с профил.</p>
        : !mine?.data ? <p>Обектите ви не са налични. Опитайте отново по-късно.</p>
        : list.length === 0 ? <p>Нямате вписани обекти. Обърнете се към управителя.</p>
        : list.map((b, i) => (
          <section key={`${b.unitId}-${b.roleLabel}`} className="unit-block">
            <h2>{b.where}</h2>
            <p>{b.roleLabel}{b.share ? ` · вашият дял: ${b.share}` : ""}</p>
            {b.statementHref && <p className="amount">{balances[i] ?? "Сметката още не е налична"}</p>}
            {b.statementHref && <a className="primary" href={b.statementHref}>Моята сметка</a>}
          </section>
        ))}
      {session && (
        <form method="post" action={LOGOUT}>
          <p>Влезли сте като {session.name ?? "потребител без име"}.</p>
          <button type="submit">Изход</button>
        </form>
      )}
    </>
  );
}
