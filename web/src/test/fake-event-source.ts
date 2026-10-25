/** Just enough of EventSource for the tests, which play the server and the network. */
export class FakeEventSource {
	static CONNECTING = 0;
	static OPEN = 1;
	static CLOSED = 2;
	static all: FakeEventSource[] = [];

	readyState = FakeEventSource.CONNECTING;
	onopen: (() => void) | null = null;
	onerror: (() => void) | null = null;
	closed = false;
	private listeners = new Map<string, ((e: { data: string }) => void)[]>();

	constructor(readonly url: string) {
		FakeEventSource.all.push(this);
	}

	static reset() {
		FakeEventSource.all = [];
	}

	static get latest(): FakeEventSource {
		return FakeEventSource.all[FakeEventSource.all.length - 1];
	}

	addEventListener(event: string, listener: (e: { data: string }) => void) {
		this.listeners.set(event, [...(this.listeners.get(event) ?? []), listener]);
	}

	close() {
		this.closed = true;
		this.readyState = FakeEventSource.CLOSED;
	}

	// The server's side

	open() {
		this.readyState = FakeEventSource.OPEN;
		this.onopen?.();
	}

	send(event: string, data: unknown) {
		for (const listener of this.listeners.get(event) ?? []) listener({ data: data === null ? '' : JSON.stringify(data) });
	}

	/** The connection drops; `forGood` is a failure the browser does not retry by itself. */
	drop(forGood: boolean) {
		this.readyState = forGood ? FakeEventSource.CLOSED : FakeEventSource.CONNECTING;
		this.onerror?.();
	}
}
