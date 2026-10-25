import type { GovernmentItem, GovernmentPrescription, Medication } from './api.ts';
import { plural } from './util.ts';

/** A government prescription number to try out, and what makes it worth trying. */
export interface SamplePrescription {
	number: string;
	explanation: string;
}

/**
 * The prescriptions the government system's mock knows (gov-api/, see its README), offered on the check-in page so
 * each can be tried with a tap. Each explanation says what the patient will see, not just what is on the prescription.
 */
export const SAMPLE_PRESCRIPTIONS: readonly SamplePrescription[] = [
	{
		number: 'GOV-1001',
		explanation: 'Amoxicillin (1 pack, as prescribed) and ibuprofen (up to 2 packs): one medication taken whole, one with a maximum.'
	},
	{
		number: 'GOV-1002',
		explanation: 'Metformin (up to 3 packs) and lisinopril (1 pack, as prescribed): the same two kinds, with a larger maximum.'
	},
	{
		number: 'GOV-1003',
		explanation: 'Atorvastatin (up to 5 packs) and cetirizine (1 pack, as prescribed): if the pharmacy has fewer than 5 atorvastatin on the shelf, you can only take what is there.'
	},
	{
		number: 'GOV-1004',
		explanation: 'Omeprazole (1 pack), already collected: a prescription used in full cannot be used again, and you are told so.'
	}
];

/** A medication of a government prescription that the pharmacy carries, with the packs that can be taken of it. */
export interface Offered extends GovernmentItem {
	medication: Medication;
	cap: number;
}

/**
 * How many packs can be taken: what the government prescription allows, within the stock. Medication taken as
 * prescribed is all or nothing, so it is out of reach when the full amount is not there.
 */
export function cap(item: GovernmentItem, medication: Medication): number {
	if (item.maximum) return Math.min(item.quantity, medication.stock);
	return item.quantity <= medication.stock ? item.quantity : 0;
}

/** What the patient reads under a medication of a government prescription: what it allows, and why it cannot be taken. */
export function governmentNote(item: GovernmentItem, medication: Medication, limit: number): string {
	const allowed = item.maximum ? `up to ${plural(item.quantity, 'pack')}` : `${plural(item.quantity, 'pack')}, as prescribed`;
	if (limit > 0) return allowed;
	return medication.stock === 0 ? `${allowed} · out of stock` : `${allowed} · not enough in stock`;
}

/**
 * Splits what a government prescription lists into what the pharmacy carries (to choose from) and what it does not
 * (to tell the patient about: it would otherwise just be missing from the list).
 */
export function offered(
	government: GovernmentPrescription | null,
	medications: Medication[] | null
): { stocked: Offered[]; unstocked: GovernmentItem[] } {
	const stocked: Offered[] = [];
	const unstocked: GovernmentItem[] = [];
	for (const item of government?.items ?? []) {
		const medication = medications?.find((m) => m.id === item.medicationId);
		if (medication) stocked.push({ ...item, medication, cap: cap(item, medication) });
		else unstocked.push(item);
	}
	return { stocked, unstocked };
}
