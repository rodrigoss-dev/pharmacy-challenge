import adapter from '@sveltejs/adapter-static';
import { sveltekit } from '@sveltejs/kit/vite';
import { svelteTesting } from '@testing-library/svelte/vite';
import { defineConfig } from 'vitest/config';

export default defineConfig({
	test: {
		include: ['src/**/*.test.ts'],
		// Logic runs in plain Node. The pages ask for a browser of their own (jsdom) in the first line of their test file.
		environment: 'node',
		setupFiles: ['./src/test/setup.ts'],
		restoreMocks: true
	},
	plugins: [
		sveltekit({
			compilerOptions: {
				// Force runes mode for the project, except for libraries. Can be removed in svelte 6.
				runes: ({ filename }) =>
					filename.split(/[/\\]/).includes('node_modules') ? undefined : true
			},
			// A single-page app: any static file server can host build/, answering every page path with the 200.html
			// shell (see nginx.conf). It calls the backend at VITE_API_URL (see src/lib/api.ts).
			adapter: adapter({ fallback: '200.html' })
		}),
		svelteTesting()
	]
});
