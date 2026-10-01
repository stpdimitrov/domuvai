/**
 * The console switch (Rule: PM-DEBT-011). The console names debtors and what they owe, and there is no sign-in yet
 * (ADR-011), so a server serves it only where it is switched on: `DOMUVAI_CONSOLE=on` in the server's environment,
 * or `next dev`, which `npm run dev` binds to this machine alone. Read from the running server's environment, never
 * baked into the build — one build serves either way, and a deployment that forgets the switch is closed.
 */
export const consoleOpen = (): boolean =>
  process.env.DOMUVAI_CONSOLE === "on" || process.env.NODE_ENV === "development";
