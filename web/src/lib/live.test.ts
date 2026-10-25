import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { FakeEventSource } from '../test/fake-event-source.ts';
import { liveStream, type ConnectionState } from './live.ts';

const latest = () => FakeEventSource.latest;

let visibility: 'visible' | 'hidden';
let visibilityListener: (() => void) | undefined;
let states: ConnectionState[];

beforeEach(() => {
	FakeEventSource.reset();
	states = [];
	visibility = 'visible';
	visibilityListener = undefined;
	vi.useFakeTimers();
	vi.spyOn(Math, 'random').mockReturnValue(0.5); // retryDelay: 1x
	vi.stubGlobal('EventSource', FakeEventSource);
	vi.stubGlobal('document', {
		get visibilityState() {
			return visibility;
		},
		addEventListener: (_event: string, listener: () => void) => (visibilityListener = listener),
		removeEventListener: () => (visibilityListener = undefined)
	});
});

afterEach(() => {
	vi.useRealTimers();
	vi.unstubAllGlobals();
});

function follow(handlers: Record<string, (data: unknown) => void> = {}) {
	return liveStream('/api/board/live', handlers, (state) => states.push(state));
}

describe('liveStream', () => {
	it('connects to the backend, and is live once it answers', () => {
		follow();

		expect(latest().url).toBe('http://localhost:8080/api/board/live');
		expect(states).toEqual(['connecting']);
		latest().open();
		expect(states).toEqual(['connecting', 'live']);
	});

	it('hands every named event to its handler, as parsed JSON', () => {
		const board = vi.fn();
		const other = vi.fn();
		follow({ board, other });
		latest().open();

		latest().send('board', { nowCalling: [3] });
		latest().send('other', { x: 1 });

		expect(board).toHaveBeenCalledExactlyOnceWith({ nowCalling: [3] });
		expect(other).toHaveBeenCalledExactlyOnceWith({ x: 1 });
	});

	it('hands an event without data over as null', () => {
		const handler = vi.fn();
		follow({ ping: handler });

		latest().send('ping', null);

		expect(handler).toHaveBeenCalledWith(null);
	});

	it('says updates are delayed when the backend cannot read its data, and is live again with the next update', () => {
		follow({ board: () => {} });
		latest().open();

		latest().send('unavailable', { error: 'try later' });
		expect(states.at(-1)).toBe('delayed');

		latest().send('board', {});
		expect(states.at(-1)).toBe('live');
	});

	it('only reports a change of state once', () => {
		follow({ board: () => {} });
		latest().open();
		latest().send('board', {});
		latest().send('board', {});

		expect(states).toEqual(['connecting', 'live']);
	});

	it('leaves reconnecting to the browser while it still tries', () => {
		follow();
		latest().open();

		latest().drop(false);

		expect(states.at(-1)).toBe('reconnecting');
		vi.advanceTimersByTime(120_000);
		expect(FakeEventSource.all).toHaveLength(1);
	});

	it('connects again by itself when the browser gives up, with pauses of 2s, 4s, 8s... up to 30s', () => {
		follow();
		const pauses = [2_000, 4_000, 8_000, 16_000, 30_000, 30_000];

		pauses.forEach((pause, index) => {
			latest().drop(true);
			expect(states.at(-1)).toBe('reconnecting');
			vi.advanceTimersByTime(pause - 1);
			expect(FakeEventSource.all, `before pause ${index + 1}`).toHaveLength(index + 1);
			vi.advanceTimersByTime(1);
			expect(FakeEventSource.all, `after pause ${index + 1}`).toHaveLength(index + 2);
		});
	});

	it('starts the pauses over once a connection works', () => {
		follow();
		latest().drop(true);
		vi.advanceTimersByTime(2_000);
		latest().drop(true);
		vi.advanceTimersByTime(4_000);
		latest().open();
		expect(states.at(-1)).toBe('live');

		latest().drop(true);
		vi.advanceTimersByTime(1_999);
		expect(FakeEventSource.all).toHaveLength(3);
		vi.advanceTimersByTime(1);
		expect(FakeEventSource.all).toHaveLength(4);
	});

	it('spreads the reconnections of many phones apart', () => {
		const random = vi.spyOn(Math, 'random');
		follow();

		random.mockReturnValue(0);
		latest().drop(true);
		vi.advanceTimersByTime(1_399); // 2s x 0.7
		expect(FakeEventSource.all).toHaveLength(1);
		vi.advanceTimersByTime(1);
		expect(FakeEventSource.all).toHaveLength(2);

		random.mockReturnValue(1);
		latest().drop(true); // the second failure in a row: 4s, give or take 30%, so up to 5.2s
		vi.advanceTimersByTime(5_198); // (floating point makes it 5199.99...)
		expect(FakeEventSource.all).toHaveLength(2);
		vi.advanceTimersByTime(2);
		expect(FakeEventSource.all).toHaveLength(3);
	});

	it('connects again at once when the patient looks at a suspended tab again', () => {
		follow();
		latest().drop(true);

		visibilityListener?.();

		expect(FakeEventSource.all).toHaveLength(2);
		vi.advanceTimersByTime(60_000);
		expect(FakeEventSource.all, 'the pause that was running is not kept as well').toHaveLength(2);
	});

	it('does not reconnect when the tab is still hidden, or the connection is fine', () => {
		follow();
		latest().drop(true);
		visibility = 'hidden';
		visibilityListener?.();
		expect(FakeEventSource.all).toHaveLength(1);

		visibility = 'visible';
		latest().open();
		visibilityListener?.();
		expect(FakeEventSource.all).toHaveLength(1);
	});

	it('stops for good when closed: no listener, no connection, no retry', () => {
		const stream = follow();
		latest().drop(true);

		stream.close();

		expect(states.at(-1)).toBe('closed');
		expect(latest().closed).toBe(true);
		expect(visibilityListener).toBeUndefined();
		vi.advanceTimersByTime(120_000);
		expect(FakeEventSource.all).toHaveLength(1);
	});
});
