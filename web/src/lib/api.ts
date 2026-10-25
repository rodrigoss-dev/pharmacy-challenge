// The backend's HTTP API: a small fetch wrapper and the shapes of its JSON (see infra/.../api/Dtos.kt).

/** Where the backend runs. Set VITE_API_URL at build time; the default is the Quarkus dev server. */
const API_URL = (import.meta.env.VITE_API_URL ?? 'http://localhost:8080').replace(/\/$/, '');

/** The full URL of a backend path, e.g. `/api/board`. */
export const apiUrl = (path: string) => API_URL + path;

export type Status =
	| 'RECEIVED'
	| 'AWAITING_APPROVAL'
	| 'AWAITING_PACKAGING'
	| 'READY_FOR_PICKUP'
	| 'CALLED'
	| 'COLLECTED'
	| 'OUT_OF_STOCK'
	| 'REJECTED'
	| 'NO_SHOW';

export const FINAL_STATUSES: readonly Status[] = ['COLLECTED', 'OUT_OF_STOCK', 'REJECTED', 'NO_SHOW'];

export interface Medication {
	id: string;
	name: string;
	category: string;
	stock: number;
}

export interface Item {
	medicationId: string;
	name: string;
	quantity: number;
}

export interface CheckInResponse {
	id: string;
	ticketNumber: number;
}

export interface GovernmentItem {
	medicationId: string;
	/** Packs left to collect. */
	quantity: number;
	/** True: take anything from 1 up to `quantity`. False: `quantity` is what was prescribed, taken as it is. */
	maximum: boolean;
}

/** What a government prescription number lets the patient collect, as the government system has it right now. */
export interface GovernmentPrescription {
	number: string;
	items: GovernmentItem[];
}

export interface Tracking {
	id: string;
	ticketNumber: number;
	patientName: string;
	status: Status;
	statusReason: string | null;
	items: Item[];
	peopleAhead: number;
	delayed: boolean;
	checkedInAt: string;
	updatedAt: string;
}

export interface Board {
	nowCalling: number[];
	ready: number[];
	inProgress: number[];
}

export interface StaffPrescription {
	id: string;
	ticketNumber: number;
	patientName: string;
	status: Status;
	statusReason: string | null;
	items: Item[];
	checkedInAt: string;
	updatedAt: string;
	/** The government prescription the patient checked in with, if any. */
	governmentNumber: string | null;
}

export interface WorkQueues {
	stockCheck: StaffPrescription[];
	pharmacist: StaffPrescription[];
	packaging: StaffPrescription[];
	pickup: StaffPrescription[];
}

export interface Health {
	/** While down nothing can be saved: requests get a 503 and the live screens say updates are delayed. */
	database: 'up' | 'down';
	broker: 'up' | 'down';
	/** Why RabbitMQ refuses new messages (memory or disk alarm). */
	brokerBlocked: string | null;
	/** Null while the database is down. */
	pendingEvents: number | null;
	oldestPendingEventSeconds: number | null;
	/** Stock checks taking longer than they should; the backend retries them by itself. */
	stalledStockChecks: number | null;
	deadLetters: number | null;
	/** Commits to the government system it has not accepted yet (it is down, or slow). */
	pendingGovernmentCommits: number | null;
	oldestPendingGovernmentCommitSeconds: number | null;
	/** Commits the government refused for good: only a person can sort those out. */
	rejectedGovernmentCommits: number | null;
	liveStreams: number;
}

export class ApiError extends Error {
	readonly status: number;
	/** From the Retry-After header, in seconds. */
	readonly retryAfter: number | null;

	constructor(status: number, message: string, retryAfter: number | null = null) {
		super(message);
		this.status = status;
		this.retryAfter = retryAfter;
	}

	/** Worth trying again: the network failed, or the backend is briefly unavailable (503). */
	get retryable(): boolean {
		return this.status === 0 || this.status === 503;
	}
}

/**
 * How long one request may take before the page gives up on it: longer than anything the backend itself waits for (5s
 * for the government system, 10s for a statement), so a slow answer still arrives, but a connection that stalled does
 * not leave the patient looking at "Checking you in..." for minutes.
 */
export const REQUEST_TIMEOUT_MS = 15_000;

const UNREACHABLE = "We can't reach the pharmacy system. Check your connection and try again.";
const TOO_SLOW = 'The pharmacy system is taking too long to answer. Please try again.';

/**
 * Calls the backend. With `retries`, a network failure, a request that took too long or a 503 is retried after a pause
 * (the backend's Retry-After, or 1s, 2s, 4s...). Only for requests that are safe to repeat: reads, and the check-in,
 * which carries its own id.
 */
export async function api<T>(
	path: string,
	{
		method = 'GET',
		body,
		retries = 0,
		timeoutMs = REQUEST_TIMEOUT_MS
	}: { method?: string; body?: unknown; retries?: number; timeoutMs?: number } = {}
): Promise<T> {
	for (let attempt = 0; ; attempt++) {
		try {
			return await request<T>(path, method, body, timeoutMs);
		} catch (e) {
			if (!(e instanceof ApiError) || !e.retryable || attempt >= retries) throw e;
			const seconds = Math.min(e.retryAfter ?? 2 ** attempt, 10);
			await new Promise((resolve) => setTimeout(resolve, seconds * 1000 * (0.8 + Math.random() * 0.4)));
		}
	}
}

async function request<T>(path: string, method: string, body: unknown, timeoutMs: number): Promise<T> {
	// Covers the body as well: an answer that starts and then stalls is cut off too.
	const signal = AbortSignal.timeout(timeoutMs);
	let response: Response;
	try {
		response = await fetch(apiUrl(path), {
			method,
			headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
			body: body === undefined ? undefined : JSON.stringify(body),
			signal
		});
	} catch {
		throw new ApiError(0, signal.aborted ? TOO_SLOW : UNREACHABLE);
	}
	let data: { error?: string } | null = null;
	try {
		data = await response.json();
	} catch {
		if (signal.aborted) throw new ApiError(0, TOO_SLOW);
	}
	if (!response.ok) {
		const retryAfter = Number(response.headers.get('Retry-After')) || null;
		throw new ApiError(response.status, data?.error ?? `Request failed (${response.status})`, retryAfter);
	}
	return data as T;
}
