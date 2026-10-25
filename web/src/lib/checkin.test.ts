import { describe, expect, it } from 'vitest';
import { checkInIds } from './checkin.ts';

const prescription = (name: string, packs: number, governmentNumber?: string) => [name, [{ medicationId: 'ibuprofen', quantity: packs }], governmentNumber];

describe('the id a check-in is sent with', () => {
	it('stays the same while the same details are sent again, so a retry gets the same ticket', () => {
		const idFor = checkInIds();

		const first = idFor(prescription('Maria', 2));

		expect(idFor(prescription('Maria', 2))).toBe(first);
		expect(idFor(prescription('Maria', 2))).toBe(first);
		expect(first).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
	});

	it('changes when the patient changes what they ask for after a failed attempt', () => {
		const idFor = checkInIds();
		const first = idFor(prescription('Maria', 2));

		const other = idFor(prescription('Maria', 1));

		expect(other).not.toBe(first);
		expect(idFor(prescription('Maria', 1))).toBe(other);
	});

	it.each([
		['the name', prescription('Mario', 2)],
		['the government prescription', prescription('Maria', 2, 'GOV-1001')]
	])('changes with %s', (_what, changed) => {
		const idFor = checkInIds();
		const first = idFor(prescription('Maria', 2));

		expect(idFor(changed)).not.toBe(first);
	});

	it('is one id per page: another page gets its own', () => {
		expect(checkInIds()(prescription('Maria', 2))).not.toBe(checkInIds()(prescription('Maria', 2)));
	});
});
