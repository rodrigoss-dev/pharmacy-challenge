import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, ApiError } from './api.ts';

function reply(status: number, body: unknown, headers: Record<string, string> = {}) {
	return new Response(body === undefined ? null : JSON.stringify(body), { status, headers });
}

const fetchMock = vi.fn<typeof fetch>();

/** The error a call ends in. */
const failure = (call: Promise<unknown>) =>
	call.then(
		() => {
			throw new Error('The call was expected to fail');
		},
		(e: unknown) => e as ApiError
	);

beforeEach(() => {
	vi.stubGlobal('fetch', fetchMock);
	fetchMock.mockReset();
});

afterEach(() => {
	vi.useRealTimers();
	vi.unstubAllGlobals();
});

describe('api', () => {
	it('reads the JSON of an answer, from the backend URL', async () => {
		fetchMock.mockResolvedValue(reply(200, { ok: 1 }));

		await expect(api('/api/board')).resolves.toEqual({ ok: 1 });

		expect(fetchMock.mock.calls[0][0]).toBe('http://localhost:8080/api/board');
		expect(fetchMock.mock.calls[0][1]).toMatchObject({ method: 'GET', headers: {}, body: undefined });
	});

	it('sends a body as JSON', async () => {
		fetchMock.mockResolvedValue(reply(201, { id: 'x' }));

		await api('/api/prescriptions', { method: 'POST', body: { a: 1 } });

		expect(fetchMock.mock.calls[0][1]).toMatchObject({
			method: 'POST',
			headers: { 'Content-Type': 'application/json' },
			body: '{"a":1}'
		});
	});

	it('turns the backend error into one the patient can read', async () => {
		fetchMock.mockResolvedValue(reply(409, { error: 'Prescription #7 is already collected' }));

		const error = await failure(api('/x'));

		expect(error).toBeInstanceOf(ApiError);
		expect(error).toMatchObject({ status: 409, message: 'Prescription #7 is already collected', retryable: false });
	});

	it('copes with an error that is not JSON', async () => {
		fetchMock.mockResolvedValue(new Response('<html>bad gateway</html>', { status: 502 }));

		await expect(api('/x')).rejects.toMatchObject({ status: 502, message: 'Request failed (502)' });
	});

	it('says so when the network fails, and calls that retryable', async () => {
		fetchMock.mockRejectedValue(new TypeError('Failed to fetch'));

		const error = await failure(api('/x'));

		expect(error).toMatchObject({ status: 0, retryable: true });
		expect(error.message).toContain("can't reach the pharmacy system");
	});

	describe('retries', () => {
		beforeEach(() => {
			vi.useFakeTimers();
			vi.spyOn(Math, 'random').mockReturnValue(0.5); // no jitter: 0.8 + 0.5 * 0.4 = 1
		});

		it('waits as long as a 503 asks, then tries again', async () => {
			fetchMock.mockResolvedValueOnce(reply(503, { error: 'busy' }, { 'Retry-After': '3' })).mockResolvedValueOnce(reply(200, { ok: 1 }));

			const result = api('/x', { retries: 1 });
			await vi.advanceTimersByTimeAsync(2_999);
			expect(fetchMock).toHaveBeenCalledTimes(1);
			await vi.advanceTimersByTimeAsync(1);

			await expect(result).resolves.toEqual({ ok: 1 });
			expect(fetchMock).toHaveBeenCalledTimes(2);
		});

		it('backs off 1s, 2s, 4s without a Retry-After', async () => {
			fetchMock.mockRejectedValueOnce(new TypeError('x')).mockRejectedValueOnce(new TypeError('x')).mockResolvedValueOnce(reply(200, 1));

			const result = api('/x', { retries: 2 });
			await vi.advanceTimersByTimeAsync(1_000);
			expect(fetchMock).toHaveBeenCalledTimes(2);
			await vi.advanceTimersByTimeAsync(2_000);

			await expect(result).resolves.toBe(1);
			expect(fetchMock).toHaveBeenCalledTimes(3);
		});

		it('never waits more than 10 seconds', async () => {
			fetchMock.mockResolvedValueOnce(reply(503, {}, { 'Retry-After': '120' })).mockResolvedValueOnce(reply(200, 1));

			const result = api('/x', { retries: 1 });
			await vi.advanceTimersByTimeAsync(9_999);
			expect(fetchMock).toHaveBeenCalledTimes(1);
			await vi.advanceTimersByTimeAsync(1);

			await expect(result).resolves.toBe(1);
		});

		it('gives up after the retries, with the last error', async () => {
			fetchMock.mockImplementation(async () => reply(503, { error: 'still down' })); // a new Response each time: a body is read once

			const result = failure(api('/x', { retries: 2 }));
			await vi.advanceTimersByTimeAsync(60_000);

			expect(await result).toMatchObject({ status: 503, message: 'still down' });
			expect(fetchMock).toHaveBeenCalledTimes(3);
		});

		it('does not retry what retrying cannot fix', async () => {
			fetchMock.mockResolvedValue(reply(400, { error: 'Please tell us your name' }));

			await expect(api('/x', { retries: 3 })).rejects.toMatchObject({ status: 400 });
			expect(fetchMock).toHaveBeenCalledTimes(1);
		});

		it('does not retry unless asked to', async () => {
			fetchMock.mockResolvedValue(reply(503, {}));

			await expect(api('/x')).rejects.toMatchObject({ status: 503 });
			expect(fetchMock).toHaveBeenCalledTimes(1);
		});
	});

	describe('a request that takes too long', () => {
		/** A fetch that never answers, until its signal gives up. */
		function stalled() {
			fetchMock.mockImplementation(
				(_url, init) =>
					new Promise((_resolve, reject) => {
						init?.signal?.addEventListener('abort', () => reject(new DOMException('The operation timed out.', 'TimeoutError')));
					})
			);
		}

		it('is given up on, and reported as slow rather than as offline', async () => {
			stalled();

			const error = await failure(api('/x', { timeoutMs: 30 }));

			expect(error).toBeInstanceOf(ApiError);
			expect(error).toMatchObject({ status: 0, retryable: true });
			expect(error.message).toContain('taking too long');
		});

		it('is given up on when the answer starts and then stalls', async () => {
			fetchMock.mockImplementation((_url, init) => {
				const response = new Response(null, { status: 200 });
				vi.spyOn(response, 'json').mockImplementation(
					() => new Promise((_resolve, reject) => init?.signal?.addEventListener('abort', () => reject(new Error('aborted'))))
				);
				return Promise.resolve(response);
			});

			await expect(api('/x', { timeoutMs: 30 })).rejects.toMatchObject({ status: 0, message: expect.stringContaining('taking too long') });
		});

		it('is tried again, when retries are allowed', async () => {
			vi.spyOn(Math, 'random').mockReturnValue(0);
			stalled();
			fetchMock.mockImplementationOnce(
				(_url, init) =>
					new Promise((_resolve, reject) => {
						init?.signal?.addEventListener('abort', () => reject(new DOMException('timed out', 'TimeoutError')));
					})
			);
			fetchMock.mockResolvedValueOnce(reply(200, { ok: 1 }));

			await expect(api('/x', { timeoutMs: 20, retries: 1 })).resolves.toEqual({ ok: 1 });
			expect(fetchMock).toHaveBeenCalledTimes(2);
		});
	});
});
