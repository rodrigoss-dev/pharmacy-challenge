import { apiUrl } from './api.ts';

/** `delayed`: connected, but the backend cannot read the pharmacy's data right now; what is shown may be behind. */
export type ConnectionState = 'connecting' | 'live' | 'delayed' | 'reconnecting' | 'closed';

/**
 * Subscribes to one of the backend's Server-Sent Events streams (`path`, e.g. `/api/board/live`). EventSource
 * reconnects by itself after network errors, but gives up for good on some failures (e.g. a proxy error page); this
 * keeps trying, with growing, randomized pauses so a waiting room full of phones does not reconnect in step. It
 * reports whether the page is live, and when the backend says its updates are delayed (an `unavailable` event).
 * Call `close()` when the page goes away.
 */
export function liveStream(
	path: string,
	handlers: Record<string, (data: unknown) => void>,
	onState: (state: ConnectionState) => void
): { close(): void } {
	let source: EventSource | undefined;
	let retryTimer: ReturnType<typeof setTimeout> | undefined;
	let failures = 0;
	let state: ConnectionState | undefined;

	function set(next: ConnectionState) {
		if (next === state) return;
		state = next;
		onState(next);
	}

	function connect() {
		source = new EventSource(apiUrl(path));
		source.onopen = () => {
			failures = 0;
			set('live');
		};
		source.onerror = () => {
			set('reconnecting');
			if (source?.readyState === EventSource.CLOSED) {
				clearTimeout(retryTimer);
				retryTimer = setTimeout(connect, retryDelay(failures++));
			}
		};
		// The backend could not read the database: keep what is on screen, and say updates are delayed.
		source.addEventListener('unavailable', () => set('delayed'));
		for (const [event, handler] of Object.entries(handlers)) {
			source.addEventListener(event, (e) => {
				set('live');
				handler(e.data ? JSON.parse(e.data) : null);
			});
		}
	}

	/** 2s, 4s, 8s... up to 30s, give or take 30%. */
	function retryDelay(attempt: number): number {
		return Math.min(30_000, 2_000 * 2 ** attempt) * (0.7 + Math.random() * 0.6);
	}

	// Phones suspend background tabs; reconnect straight away when the patient looks at the screen again.
	function onVisible() {
		if (document.visibilityState === 'visible' && source?.readyState === EventSource.CLOSED) {
			clearTimeout(retryTimer);
			connect();
		}
	}

	document.addEventListener('visibilitychange', onVisible);
	set('connecting');
	connect();

	return {
		close() {
			document.removeEventListener('visibilitychange', onVisible);
			clearTimeout(retryTimer);
			source?.close();
			set('closed');
		}
	};
}
