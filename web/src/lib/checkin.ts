import { uuid } from './util.ts';

/**
 * The id a check-in is sent with. The same details keep the same id, so a retry after a lost response gets the ticket it
 * already has instead of a second one. Different details (the patient changed their mind after a failed attempt) get a
 * new id: the backend refuses an id it has seen with other details, rather than hand back the old ticket.
 */
export function checkInIds(): (details: unknown) => string {
	let id = uuid();
	let sent: string | null = null;
	return (details) => {
		const signature = JSON.stringify(details);
		if (sent !== null && signature !== sent) id = uuid();
		sent = signature;
		return id;
	};
}
