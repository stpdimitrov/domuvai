// Rule: PM-SYS-015 — run after `npm run build`: sums the gzip size of the JS and CSS each (resident) route loads and
// fails the build when any route is over web/budget.json's entry in force today in Sofia. Polyfills are left out:
// they are `nomodule`, and the floor's browser does not load them.
import { readFileSync } from "node:fs";
import { gzipSync } from "node:zlib";
import { check, residentRoutes } from "../lib/budget.ts";
import { sofiaToday } from "../lib/payment.ts";

const read = (path: string) => readFileSync(new URL(`../${path}`, import.meta.url));
const pages = JSON.parse(read(".next/app-build-manifest.json").toString()).pages;
const sizes = residentRoutes(pages).map(({ route, files }) => ({
  route,
  bytes: files.reduce((sum, f) => sum + gzipSync(read(`.next/${f}`)).length, 0),
}));
const verdict = check(sizes, JSON.parse(read("budget.json").toString()).entries, sofiaToday());
console.log(verdict.lines.join("\n"));
process.exit(verdict.ok ? 0 : 1);
