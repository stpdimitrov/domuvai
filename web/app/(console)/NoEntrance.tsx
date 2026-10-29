import Link from "next/link";

/**
 * What an entrance screen says when the API lists no entrance (#363). The list is each person's own: where they hold a role today
 * (Rule: PM-SEC-001), the administrator's every one — so an empty list is either nothing registered yet or no role held, and the
 * session does not say which: it says both, and never tells the person to register one, which only the administrator may.
 */
// Rule: PM-SEC-001
export default function NoEntrance() {
  return (
    <>
      Няма вход, който да ви покажем: или още не е регистриран вход, или днес не заемате роля в нито един. Системата показва на всеки само входовете, в които има роля.{" "}
      <Link href="/registry">Регистър на входове и лица</Link>
    </>
  );
}
