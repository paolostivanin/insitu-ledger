import { defineConfig } from 'vitest/config';
import { svelte } from '@sveltejs/vite-plugin-svelte';

export default defineConfig({
	plugins: [svelte()],
	resolve: { conditions: ['browser'] },
	test: {
		environment: 'jsdom',
		setupFiles: ['src/lib/__mocks__/dom-setup.ts'],
		include: ['src/**/*.test.ts'],
		alias: {
			'$lib': '/src/lib',
			'$app/stores': '/src/lib/__mocks__/app-stores.ts',
			'$app/navigation': '/src/lib/__mocks__/app-navigation.ts'
		}
	}
});
