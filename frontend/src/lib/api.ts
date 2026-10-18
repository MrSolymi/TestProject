// Empty in dev (the Vite proxy makes /api same-origin). In production set VITE_API_URL at build
// time to the API origin, e.g. https://api.example.com
const BASE = ((import.meta.env.VITE_API_URL as string | undefined) ?? '').replace(/\/$/, '');

export class ApiError extends Error {
	constructor(
		readonly status: number,
		message: string,
		readonly fieldErrors: Record<string, string> = {}
	) {
		super(message);
	}
}

type RequestOptions = { method?: 'GET' | 'POST'; body?: unknown };

export async function api<T = void>(
	path: string,
	{ method = 'GET', body }: RequestOptions = {}
): Promise<T> {
	const res = await fetch(`${BASE}${path}`, {
		method,
		// The session lives in an HttpOnly cookie set by the backend.
		credentials: 'include',
		headers: {
			// Required by the backend on state-changing requests (CSRF guard).
			'X-Requested-By': 'spa',
			...(body !== undefined && { 'Content-Type': 'application/json' })
		},
		body: body === undefined ? undefined : JSON.stringify(body)
	});

	if (!res.ok) {
		const problem = await res.json().catch(() => ({}));
		throw new ApiError(res.status, problem.detail ?? res.statusText, problem.errors ?? {});
	}
	return (res.status === 204 ? undefined : await res.json()) as T;
}
