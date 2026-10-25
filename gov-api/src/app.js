import { createServer } from 'node:http';
import { GovError, createStore } from './store.js';

const MAX_BODY_BYTES = 64 * 1024;

// No authentication: out of scope. The real system would identify the pharmacy on every call.
//
//   GET  /prescriptions/{number}           the prescription, with what is left to dispense
//   POST /prescriptions/{number}/commits   mark quantities as used: { commitId, items: [{ productId, quantity }] }
//   GET  /prescriptions/{number}/commits   what was committed so far (to see what the pharmacy sent)
//   POST /admin/reset                      back to the seed data
//   GET  /health
export function createApp({ store = createStore(), log = () => {} } = {}) {
	const routes = [
		['GET', /^\/health$/, () => reply(200, { status: 'ok' })],
		['POST', /^\/admin\/reset$/, () => {
			store.reset();
			return reply(200, { status: 'reset' });
		}],
		['GET', /^\/prescriptions\/([^/]+)$/, ([number]) => {
			const prescription = store.find(number);
			return prescription ? reply(200, prescription) : reply(404, notFound(number));
		}],
		['GET', /^\/prescriptions\/([^/]+)\/commits$/, ([number]) => {
			const commits = store.commitsOf(number);
			return commits ? reply(200, commits) : reply(404, notFound(number));
		}],
		['POST', /^\/prescriptions\/([^/]+)\/commits$/, async ([number], request) => {
			const { commit, replayed } = store.commit(number, await readJson(request));
			// 201 the first time, 200 for the same commit sent again: either way it is recorded.
			return reply(replayed ? 200 : 201, commit);
		}]
	];

	return createServer(async (request, response) => {
		const started = performance.now();
		const { status, body } = await handle(request).catch(failed);
		const text = JSON.stringify(body);
		response.writeHead(status, { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(text) });
		response.end(text);
		log(`${request.method} ${request.url} -> ${status} (${Math.round(performance.now() - started)} ms)` + (status >= 400 ? `: ${body.error}` : ''));
	});

	async function handle(request) {
		const path = new URL(request.url, 'http://localhost').pathname.replace(/\/+$/, '') || '/';
		let pathMatched = false;
		for (const [method, pattern, handler] of routes) {
			const match = pattern.exec(path);
			if (!match) continue;
			pathMatched = true;
			if (method === request.method) return handler(match.slice(1).map(decode), request);
		}
		return pathMatched ? reply(405, { error: 'Method not allowed' }) : reply(404, { error: 'Not found' });
	}
}

const reply = (status, body) => ({ status, body });

/** A number typed by hand can carry anything, and a bad `%` escape in it is the caller's mistake, not ours. */
function decode(segment) {
	try {
		return decodeURIComponent(segment);
	} catch {
		throw new GovError(400, 'Malformed path: the prescription number has an invalid % escape');
	}
}

const notFound = (number) => ({ error: `Prescription ${String(number).trim().toUpperCase()} not found` });

function failed(e) {
	if (e instanceof GovError) return reply(e.status, { error: e.message });
	console.error(e);
	return reply(500, { error: 'Internal error' });
}

async function readJson(request) {
	const chunks = [];
	let size = 0;
	for await (const chunk of request) {
		size += chunk.length;
		if (size > MAX_BODY_BYTES) throw new GovError(413, 'Body too large');
		chunks.push(chunk);
	}
	try {
		return JSON.parse(Buffer.concat(chunks).toString('utf8'));
	} catch {
		throw new GovError(400, 'Body is not valid JSON');
	}
}
