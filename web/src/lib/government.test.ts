import { describe, expect, it } from 'vitest';
import type { GovernmentItem, GovernmentPrescription, Medication } from './api.ts';
import { cap, governmentNote, offered, SAMPLE_PRESCRIPTIONS } from './government.ts';

const medication = (id: string, stock: number): Medication => ({ id, name: id.toUpperCase(), category: 'Any', stock });
const maximum = (medicationId: string, quantity: number): GovernmentItem => ({ medicationId, quantity, maximum: true });
const asPrescribed = (medicationId: string, quantity: number): GovernmentItem => ({ medicationId, quantity, maximum: false });

describe('how many packs can be taken', () => {
	it('is the maximum, within the stock', () => {
		expect(cap(maximum('a', 2), medication('a', 60))).toBe(2);
		expect(cap(maximum('a', 5), medication('a', 3))).toBe(3);
		expect(cap(maximum('a', 25), medication('a', 60))).toBe(25);
		expect(cap(maximum('a', 25), medication('a', 18))).toBe(18);
		expect(cap(maximum('a', 2), medication('a', 0))).toBe(0);
	});

	it('is all or nothing for medication taken as prescribed', () => {
		expect(cap(asPrescribed('a', 2), medication('a', 2))).toBe(2);
		expect(cap(asPrescribed('a', 2), medication('a', 1))).toBe(0);
		expect(cap(asPrescribed('a', 2), medication('a', 0))).toBe(0);
		expect(cap(asPrescribed('a', 12), medication('a', 60))).toBe(12);
		expect(cap(asPrescribed('a', 12), medication('a', 11))).toBe(0);
	});
});

describe('what is written under a medication', () => {
	it('says what is allowed', () => {
		expect(governmentNote(maximum('a', 2), medication('a', 60), 2)).toBe('up to 2 packs');
		expect(governmentNote(maximum('a', 1), medication('a', 60), 1)).toBe('up to 1 pack');
		expect(governmentNote(asPrescribed('a', 1), medication('a', 60), 1)).toBe('1 pack, as prescribed');
	});

	it('says why it cannot be taken', () => {
		expect(governmentNote(maximum('a', 2), medication('a', 0), 0)).toBe('up to 2 packs · out of stock');
		expect(governmentNote(asPrescribed('a', 2), medication('a', 1), 0)).toBe('2 packs, as prescribed · not enough in stock');
		expect(governmentNote(asPrescribed('a', 12), medication('a', 11), 0)).toBe('12 packs, as prescribed · not enough in stock');
	});
});

describe('what the pharmacy carries of a government prescription', () => {
	const prescription: GovernmentPrescription = {
		number: 'GOV-1',
		items: [maximum('ibuprofen', 2), asPrescribed('amoxicillin', 1), maximum('zzz', 4)]
	};
	const catalog = [medication('ibuprofen', 60), medication('amoxicillin', 0)];

	it('keeps what is carried, with the packs that can be taken, and sets apart what is not', () => {
		const { stocked, unstocked } = offered(prescription, catalog);

		expect(stocked.map((item) => [item.medicationId, item.cap])).toEqual([
			['ibuprofen', 2],
			['amoxicillin', 0]
		]);
		expect(stocked[0].medication.name).toBe('IBUPROFEN');
		expect(unstocked).toEqual([maximum('zzz', 4)]);
	});

	it('offers nothing before there is a prescription or a catalog', () => {
		expect(offered(null, catalog)).toEqual({ stocked: [], unstocked: [] });
		expect(offered(prescription, null).stocked).toEqual([]);
		expect(offered(prescription, null).unstocked).toHaveLength(3);
	});
});

describe('the sample prescriptions', () => {
	it('each have a number the lookup accepts, listed once, and something to say about them', () => {
		const numbers = SAMPLE_PRESCRIPTIONS.map((sample) => sample.number);

		expect(numbers.length).toBeGreaterThan(0);
		expect(new Set(numbers).size).toBe(numbers.length);
		for (const { number, explanation } of SAMPLE_PRESCRIPTIONS) {
			expect(number).toBe(number.trim());
			expect(number.length).toBeLessThanOrEqual(40); // the most the backend takes
			expect(explanation.trim()).not.toBe('');
		}
	});
});
