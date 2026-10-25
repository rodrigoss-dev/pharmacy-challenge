import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { latestOnly, poll } from './polling.ts';

beforeEach(() => vi.useFakeTimers());
afterEach(() => vi.useRealTimers());

/** A promise the test settles by hand. */
function later<T = void>() {
	let resolve!: (value: T) => void;
	const promise = new Promise<T>((r) => (resolve = r));
	return { promise, resolve };
}

describe('poll', () => {
	it('runs at once, then again a pause after each run finished', async () => {
		const task = vi.fn(async () => {});

		poll(task, 2000);
		await vi.advanceTimersByTimeAsync(0);
		expect(task).toHaveBeenCalledTimes(1);
		await vi.advanceTimersByTimeAsync(1999);
		expect(task).toHaveBeenCalledTimes(1);
		await vi.advanceTimersByTimeAsync(1);
		expect(task).toHaveBeenCalledTimes(2);
		await vi.advanceTimersByTimeAsync(2000);
		expect(task).toHaveBeenCalledTimes(3);
	});

	it('never starts a run while the last one is still going', async () => {
		const slow = later();
		const task = vi.fn(() => slow.promise);

		poll(task, 2000);
		await vi.advanceTimersByTimeAsync(60_000);
		expect(task).toHaveBeenCalledTimes(1);

		slow.resolve();
		await vi.advanceTimersByTimeAsync(1999);
		expect(task).toHaveBeenCalledTimes(1);
		await vi.advanceTimersByTimeAsync(1);
		expect(task).toHaveBeenCalledTimes(2);
	});

	it('keeps going after a run that failed', async () => {
		const task = vi.fn().mockRejectedValueOnce(new Error('boom')).mockResolvedValue(undefined);

		poll(task, 1000);
		await vi.advanceTimersByTimeAsync(1000);

		expect(task).toHaveBeenCalledTimes(2);
	});

	it('stops when told to, even in the middle of a run', async () => {
		const slow = later();
		const task = vi.fn(() => slow.promise);

		const stop = poll(task, 1000);
		stop();
		slow.resolve();
		await vi.advanceTimersByTimeAsync(10_000);

		expect(task).toHaveBeenCalledTimes(1);
	});

	it('stops when told to between runs', async () => {
		const task = vi.fn(async () => {});

		const stop = poll(task, 1000);
		await vi.advanceTimersByTimeAsync(0);
		stop();
		await vi.advanceTimersByTimeAsync(10_000);

		expect(task).toHaveBeenCalledTimes(1);
	});
});

describe('latestOnly', () => {
	it('shows what was loaded', async () => {
		const shown: string[] = [];
		const refresh = latestOnly(async () => 'queues', (value) => shown.push(value));

		await refresh();

		expect(shown).toEqual(['queues']);
	});

	it('does not let a slow older answer replace a newer one', async () => {
		const first = later<string>();
		const second = later<string>();
		const answers = [first.promise, second.promise];
		const shown: string[] = [];
		const refresh = latestOnly(() => answers.shift()!, (value) => shown.push(value));

		const older = refresh();
		const newer = refresh();
		second.resolve('newer');
		await newer;
		first.resolve('older');
		await older;

		expect(shown).toEqual(['newer']);
	});

	it('shows an older answer that arrives first only if nothing newer was asked for', async () => {
		const first = later<string>();
		const shown: string[] = [];
		const refresh = latestOnly(() => first.promise, (value) => shown.push(value));

		const only = refresh();
		first.resolve('only');
		await only;

		expect(shown).toEqual(['only']);
	});
});
