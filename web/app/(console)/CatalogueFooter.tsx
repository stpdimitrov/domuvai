import { connection } from "next/server";
import { api } from "@/lib/api/client";
import { reach } from "@/lib/console";
import SignedIn from "./SignedIn";

/**
 * The console's footer (Rule: PM-SYS-010): the rule catalogue's version and the engine's, as the API states them on
 * this request — the pair every computed record is stamped with. The web keeps no copy, so when the API does not
 * answer the footer says so and shows no version.
 */
export default async function CatalogueFooter() {
  await connection();                                    // read when a screen is asked for, never when the build runs
  const version = await reach(() => api.GET("/api/law/version"));
  return (
    <footer className="console-foot">
      <SignedIn />
      {version?.data ? (
        <>
          Каталог на правилата v{version.data.catalogueVersion} · изчислител {version.data.engineVersion}
        </>
      ) : (
        <span style={{ color: "#8E2318" }}>Каталог на правилата: версията не е достъпна</span>
      )}
    </footer>
  );
}
