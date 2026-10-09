/**
 * Where a visitor's demo request goes (#79): the address in the running server's environment,
 * `DOMUVAI_CONTACT_EMAIL`, or nowhere. The repository holds no address. Read per request, like the sign-in settings,
 * never baked into the build. Only a plain address counts — letters, digits and `. _ + -` before the `@`, a dotted
 * host name after it — so the value goes into a `mailto:` link as it is, and a quoted value, one with `mailto:` in
 * front, or one carrying `?`, `#`, `%` or a comma counts as none: the landing never offers to write to it.
 */
export const contactEmail = (): string | null => {
  const address = process.env.DOMUVAI_CONTACT_EMAIL?.trim() ?? "";
  return /^[A-Za-z0-9._+-]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)+$/.test(address) ? address : null;
};
