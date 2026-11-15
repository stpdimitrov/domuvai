/** @type {import('next').NextConfig} */
const nextConfig = {
  // The `web` deployable is a thin view over `api` (ADR-011): no domain logic here.
  reactStrictMode: true,
  // The posting act's photograph is posted through a server action (WEB-28), and a phone's photo is above the 1 MB default.
  experimental: { serverActions: { bodySizeLimit: "15mb" } },
};

export default nextConfig;
