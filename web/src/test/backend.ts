import { vi } from 'vitest';

export interface Call {
	method: string;
	path: string;
	body: unknown;
}

type Handler = (call: Call) => Response | Promise<Response>;

export const json = (status: number, body: unknown, headers: Record<string, string> = {}) =>
	new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ...headers } });

/**
 * A stand-in for the backend: routes are `'GET /api/board'`, with `*` for one path segment. Installs itself as `fetch`
 * and records what the page sent. A request no route answers fails the test, so a page cannot call the backend by
 * accident.
 */
export function fakeBackend(routes: Record<string, Handler>) {
	const calls: Call[] = [];
	const table = Object.entries(routes).map(([route, handler]) => {
		const [method, path] = route.split(' ');
		return { method, pattern: new RegExp('^' + path.replaceAll('*', '[^/]+') + '$'), handler };
	});

	const backend = {
		calls,
		/** The requests made so far to `route`. */
		to(route: string): Call[] {
			const [method, path] = route.split(' ');
			const pattern = new RegExp('^' + path.replaceAll('*', '[^/]+') + '$');
			return calls.filter((call) => call.method === method && pattern.test(call.path));
		},
		/** Answers a route differently from now on. */
		on(route: string, handler: Handler) {
			const [method, path] = route.split(' ');
			table.unshift({ method, pattern: new RegExp('^' + path.replaceAll('*', '[^/]+') + '$'), handler });
		}
	};

	vi.stubGlobal(
		'fetch',
		vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
			const url = new URL(String(input));
			const call: Call = {
				method: init?.method ?? 'GET',
				path: url.pathname,
				body: init?.body ? JSON.parse(String(init.body)) : undefined
			};
			calls.push(call);
			const route = table.find((r) => r.method === call.method && r.pattern.test(call.path));
			if (!route) throw new Error(`The page called ${call.method} ${call.path}, which the test does not expect`);
			return route.handler(call);
		})
	);
	return backend;
}
