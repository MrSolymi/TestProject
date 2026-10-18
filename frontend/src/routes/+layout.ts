import { redirect } from '@sveltejs/kit';
import { auth } from '#lib/auth.svelte.js';
import type { LayoutLoad } from './$types';

// Static SPA: nothing is rendered on a server, the fallback index.html boots the app in the browser.
export const ssr = false;
export const prerender = false;

const PUBLIC_PATHS = ['/login', '/register'];

export const load: LayoutLoad = async ({ url }) => {
	if (!auth.loaded) await auth.refresh();

	const path = url.pathname.replace(/\/$/, '') || '/';
	const isPublic = PUBLIC_PATHS.includes(path);
	if (!auth.user && !isPublic) redirect(307, '/login');
	if (auth.user && isPublic) redirect(307, '/');
};
