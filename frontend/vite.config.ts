import tailwindcss from '@tailwindcss/vite';
import adapter from '@sveltejs/adapter-static';
import { sveltekit } from '@sveltejs/kit/vite';
import { defineConfig } from 'vite';

export default defineConfig({
	plugins: [
		tailwindcss(),
		sveltekit({
			compilerOptions: {
				// Force runes mode for the project, except for libraries. Can be removed in svelte 6.
				runes: ({ filename }) =>
					filename.split(/[/\\]/).includes('node_modules') ? undefined : true
			},
			// Pure SPA: every route is served by index.html and rendered in the browser.
			adapter: adapter({ fallback: 'index.html' })
		})
	],
	server: {
		// In dev the API is same-origin through this proxy, so no CORS or cross-site cookie setup is needed.
		proxy: {
			'/api': 'http://localhost:8080'
		}
	}
});
