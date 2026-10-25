import { describe, expect, it } from 'vitest';
import type { Status, Tracking } from './api.ts';
import { currentStep, describe as say, STEPS } from './status.ts';

function tracking(status: Status, extra: Partial<Tracking> = {}): Tracking {
	return {
		id: 'id',
		ticketNumber: 17,
		patientName: 'Maria',
		status,
		statusReason: null,
		items: [],
		peopleAhead: 0,
		delayed: false,
		checkedInAt: '2026-10-25T10:00:00Z',
		updatedAt: '2026-10-25T10:00:00Z',
		...extra
	};
}

describe('what the patient reads', () => {
	it('is calm while the stock is checked, and says so when it takes long', () => {
		expect(say(tracking('RECEIVED'))).toMatchObject({ tone: 'neutral', headline: 'Checking our stock' });
		const late = say(tracking('RECEIVED', { delayed: true }));
		expect(late).toMatchObject({ tone: 'delayed', headline: 'Checking our stock' });
		expect(late.detail).toContain("you don't need to do anything");
	});

	it('counts the people ahead where they wait for staff', () => {
		expect(say(tracking('AWAITING_APPROVAL', { peopleAhead: 0 })).detail).toContain("You're next.");
		expect(say(tracking('AWAITING_APPROVAL', { peopleAhead: 1 })).detail).toContain('1 person ahead of you.');
		expect(say(tracking('AWAITING_PACKAGING', { peopleAhead: 3 })).detail).toContain('3 people ahead of you.');
	});

	it('names the number to listen for when it is ready, and when it is called', () => {
		expect(say(tracking('READY_FOR_PICKUP'))).toMatchObject({ tone: 'ready' });
		expect(say(tracking('READY_FOR_PICKUP')).detail).toContain('number 17');
		expect(say(tracking('CALLED'))).toMatchObject({ tone: 'called', headline: "It's your turn!" });
		expect(say(tracking('CALLED')).detail).toContain('number 17');
	});

	it('is friendly when it is over', () => {
		expect(say(tracking('COLLECTED'))).toMatchObject({ tone: 'ready', headline: 'All done. Get well soon!' });
	});

	it.each([
		['OUT_OF_STOCK', 'Not enough stock of Ibuprofen 400 mg', 'Not enough stock of Ibuprofen 400 mg. Please speak to a pharmacist'],
		['REJECTED', 'Allergy on file', 'Allergy on file. Please come to the counter and mention number 17.']
	] as const)('puts the reason of a %s in the sentence, with its full stop', (status, reason, expected) => {
		const message = say(tracking(status, { statusReason: reason }));
		expect(message.tone).toBe('problem');
		expect(message.detail).toContain(expected);
	});

	it('does not add a full stop to a reason that has its own punctuation', () => {
		const message = say(tracking('REJECTED', { statusReason: 'Is this the right dose?' }));
		expect(message.detail).toContain('right dose? Please come to the counter');
		expect(message.detail).not.toContain('?.');
	});

	it('copes with a reason that has spaces around it, or none', () => {
		expect(say(tracking('REJECTED', { statusReason: '  Allergy on file  ' })).detail).toContain('on file. Please');
		expect(say(tracking('OUT_OF_STOCK', { statusReason: null })).detail).toContain('.');
	});

	it('tells a no show that the medication went back on the shelf, and what to do', () => {
		const message = say(tracking('NO_SHOW'));
		expect(message.tone).toBe('problem');
		expect(message.detail).toContain('number 17');
		expect(message.detail).toContain('back on the shelf');
		expect(message.detail).toContain('check in again');
	});

	it('has a message for every status', () => {
		const all: Status[] = ['RECEIVED', 'AWAITING_APPROVAL', 'AWAITING_PACKAGING', 'READY_FOR_PICKUP', 'CALLED', 'COLLECTED', 'OUT_OF_STOCK', 'REJECTED', 'NO_SHOW'];
		for (const status of all) {
			const message = say(tracking(status, { statusReason: 'Because.' }));
			expect(message.headline, status).not.toBe('');
			expect(message.detail, status).not.toBe('');
		}
	});
});

describe('the progress steps', () => {
	it('moves one step at a time while the patient waits', () => {
		expect(STEPS.map((step) => step.label)).toEqual(['Checked in', 'Pharmacist review', 'Packing', 'Ready for pickup']);
		expect(currentStep('RECEIVED')).toBe(0);
		expect(currentStep('AWAITING_APPROVAL')).toBe(1);
		expect(currentStep('AWAITING_PACKAGING')).toBe(2);
		expect(currentStep('READY_FOR_PICKUP')).toBe(3);
	});

	it('is past the last step once the number is called, or the prescription is over', () => {
		for (const status of ['CALLED', 'COLLECTED', 'OUT_OF_STOCK', 'REJECTED', 'NO_SHOW'] as const) {
			expect(currentStep(status), status).toBe(STEPS.length);
		}
	});
});
