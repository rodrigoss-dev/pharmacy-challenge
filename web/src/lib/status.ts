import type { Status, Tracking } from './api.ts';
import { plural } from './util.ts';

export type Tone = 'neutral' | 'delayed' | 'ready' | 'called' | 'problem';

export interface StatusMessage {
	tone: Tone;
	headline: string;
	detail: string;
}

/** A reason typed by staff, as the end of a sentence: with its full stop unless it brought its own punctuation. */
function sentence(text: string | null): string {
	const trimmed = (text ?? '').trim();
	return /[.!?]$/.test(trimmed) ? trimmed : `${trimmed}.`;
}

/** What the patient reads about their prescription: one headline, one sentence, and what to do if anything. */
export function describe(p: Tracking): StatusMessage {
	const ahead = p.peopleAhead === 0 ? "You're next." : `${plural(p.peopleAhead, 'person', 'people')} ahead of you.`;
	switch (p.status) {
		case 'RECEIVED':
			return p.delayed
				? {
						tone: 'delayed',
						headline: 'Checking our stock',
						detail: "This is taking longer than usual. Our team can see it and is on it, you don't need to do anything."
					}
				: { tone: 'neutral', headline: 'Checking our stock', detail: "We're making sure we have everything on your prescription." };
		case 'AWAITING_APPROVAL':
			return {
				tone: 'neutral',
				headline: 'Waiting for a pharmacist',
				detail: `Everything is in stock. A pharmacist will review your prescription. ${ahead}`
			};
		case 'AWAITING_PACKAGING':
			return { tone: 'neutral', headline: 'Approved, now packing', detail: `Your medication is being prepared. ${ahead}` };
		case 'READY_FOR_PICKUP':
			return {
				tone: 'ready',
				headline: "Ready! We'll call you shortly",
				detail: `Stay nearby and keep this page open. We'll call number ${p.ticketNumber}.`
			};
		case 'CALLED':
			return {
				tone: 'called',
				headline: "It's your turn!",
				detail: `Please come to the pickup counter with number ${p.ticketNumber}.`
			};
		case 'COLLECTED':
			return { tone: 'ready', headline: 'All done. Get well soon!', detail: "You've collected your medication. You can close this page." };
		case 'OUT_OF_STOCK':
			return {
				tone: 'problem',
				headline: "Sorry, we're short on stock",
				detail: `${sentence(p.statusReason)} Please speak to a pharmacist at the counter: they can suggest an alternative or order it in for you.`
			};
		case 'REJECTED':
			return {
				tone: 'problem',
				headline: 'Please speak to a pharmacist',
				detail: `The pharmacist needs a word before handing out your medication: ${sentence(p.statusReason)} Please come to the counter and mention number ${p.ticketNumber}.`
			};
		case 'NO_SHOW':
			return {
				tone: 'problem',
				headline: "We couldn't reach you",
				detail: `We called number ${p.ticketNumber} and nobody came to the counter, so your medication went back on the shelf. If you are still here, please speak to a pharmacist or check in again.`
			};
	}
}

/** The milestones on the patient's progress bar. */
export const STEPS: readonly { label: string; hint: string }[] = [
	{ label: 'Checked in', hint: 'We check our stock' },
	{ label: 'Pharmacist review', hint: 'A pharmacist checks your prescription' },
	{ label: 'Packing', hint: 'Your medication is prepared' },
	{ label: 'Ready for pickup', hint: 'We call your number' }
];

/** Index of the current milestone in STEPS; STEPS.length once the last one is done. */
export function currentStep(status: Status): number {
	switch (status) {
		case 'RECEIVED':
			return 0;
		case 'AWAITING_APPROVAL':
			return 1;
		case 'AWAITING_PACKAGING':
			return 2;
		case 'READY_FOR_PICKUP':
			return 3;
		default:
			return STEPS.length;
	}
}
