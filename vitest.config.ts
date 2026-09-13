import { defineConfig } from 'vitest/config';
import { fileURLToPath } from 'node:url';
const r = (p: string) => fileURLToPath(new URL(p, import.meta.url));
export default defineConfig({
  resolve: {
    alias: {
      '@zues/kernel': r('./packages/kernel/src/index.ts'),
      '@zues/law': r('./packages/law/src/index.ts'),
      '@zues/charges': r('./packages/charges/src/index.ts'),
    },
  },
  test: { include: ['packages/**/test/**/*.test.ts', 'apps/**/test/**/*.test.ts'] },
});
