/** crypto.randomUUID only exists on https/localhost; phones on the pharmacy Wi-Fi may use plain http. */
export function uuid(): string {
	if (crypto.randomUUID) return crypto.randomUUID();
	const b = crypto.getRandomValues(new Uint8Array(16));
	b[6] = (b[6] & 0x0f) | 0x40;
	b[8] = (b[8] & 0x3f) | 0x80;
	const h = [...b].map((x) => x.toString(16).padStart(2, '0')).join('');
	return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

export function time(iso: string): string {
	return new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

export function minutesSince(iso: string): number {
	return Math.max(0, Math.floor((Date.now() - new Date(iso).getTime()) / 60000));
}

export function plural(count: number, one: string, many = `${one}s`): string {
	return `${count} ${count === 1 ? one : many}`;
}

export interface ActiveTicket {
	id: string;
	ticketNumber: number;
}

const ACTIVE_TICKET = 'pharmacy.activeTicket';

/** Remembers the patient's current ticket so they can get back to it if they close the tab. */
export const activeTicket = {
	get(): ActiveTicket | null {
		try {
			return JSON.parse(localStorage.getItem(ACTIVE_TICKET) ?? 'null');
		} catch {
			return null;
		}
	},
	set(ticket: ActiveTicket) {
		try {
			localStorage.setItem(ACTIVE_TICKET, JSON.stringify(ticket));
		} catch {
			// private mode: the patient just won't get the "continue" banner
		}
	},
	/** Forgets the ticket with this id, if it is the one remembered: looking at another ticket must not lose the current one. */
	clear(id: string) {
		try {
			if (activeTicket.get()?.id === id) localStorage.removeItem(ACTIVE_TICKET);
		} catch {
			// see above
		}
	}
};
