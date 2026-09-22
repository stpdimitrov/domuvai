/** @type {import('next').NextConfig} */
const nextConfig = {
  // The `web` deployable is a thin view over `api` (ADR-011): no domain logic here.
  reactStrictMode: true,
};

export default nextConfig;
