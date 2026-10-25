/**
 * Runs `task`, and again `everyMs` after it has finished, until the returned function is called. Unlike setInterval, a
 * slow task is never started a second time while it is still running, so a slow backend is not piled on with requests.
 */
export function poll(task: () => Promise<unknown>, everyMs: number): () => void {
	let stopped = false;
	let timer: ReturnType<typeof setTimeout> | undefined;

	async function round() {
		try {
			await task();
		} catch {
			// The task reports its own trouble; the next round is still due.
		}
		if (!stopped) timer = setTimeout(round, everyMs);
	}

	void round();
	return () => {
		stopped = true;
		clearTimeout(timer);
	};
}

/**
 * Loads and shows something that is reloaded often, from several places (a timer, a button): only the answer to the call
 * started last is shown, so a slow older answer never replaces a newer one.
 */
export function latestOnly<T>(load: () => Promise<T>, show: (value: T) => void): () => Promise<void> {
	let latest = 0;
	return async () => {
		const mine = ++latest;
		const value = await load();
		if (mine === latest) show(value);
	};
}
