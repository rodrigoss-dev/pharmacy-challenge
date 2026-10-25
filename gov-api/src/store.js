// The government's records: each prescription, and how much of it has been used so far. Kept in memory, so a restart
// (or POST /admin/reset) brings back the seed data below.

/** A rule of the government system was broken; `status` is the HTTP status the caller gets. */
export class GovError extends Error {
	constructor(status, message) {
		super(message);
		this.status = status;
	}
}

// [product id, quantity prescribed, whether that quantity is a maximum, quantity already used]
//
// The product ids are those of the pharmacy's catalog (see V2__starting_stock.sql). With `maximum` the patient may take
// anything from 1 up to the quantity; without it, the quantity is what the doctor prescribed and is taken as it is.
const SEED = {
	// One fixed product, one with a maximum.
	'GOV-1001': [
		['amoxicillin', 1, false, 0],
		['ibuprofen', 2, true, 0],
	],
	// Both kinds again, with a larger maximum.
	'GOV-1002': [
		['metformin', 3, true, 0],
		['lisinopril', 1, false, 0],
	],
	// The pharmacy has only 3 atorvastatin (see V2__starting_stock.sql): the check-in page stops at 3, below the maximum of 5.
	'GOV-1003': [
		['atorvastatin', 5, true, 0],
		['cetirizine', 1, false, 0],
	],
	// Used in full already: loading it works, but there is nothing left to dispense.
	'GOV-1004': [['omeprazole', 1, false, 1]],
};

export function createStore(seed = SEED) {
	/** number -> [{ productId, prescribed, maximum, used }] */
	let prescriptions;
	/** commit id -> { commitId, number, items: [{ productId, quantity }], committedAt } */
	let commits;

	reset();

	function reset() {
		prescriptions = new Map(
			Object.entries(seed).map(([number, items]) => [
				number,
				items.map(([productId, prescribed, maximum, used]) => ({ productId, prescribed, maximum, used }))
			])
		);
		commits = new Map();
	}

	/** The prescription as the pharmacy sees it: `quantity` is what is still left to dispense. */
	function find(number) {
		const items = prescriptions.get(normalize(number));
		if (!items) return null;
		return {
			number: normalize(number),
			items: items.map(({ productId, prescribed, maximum, used }) => ({
				productId,
				quantity: prescribed - used,
				maximum
			}))
		};
	}

	/**
	 * Marks quantities as used. All or nothing: if one product is refused, none is used.
	 *
	 * Idempotent on `commitId`: the pharmacy sends a commit from an outbox, at least once, so the same commit can arrive
	 * again after its reply was lost. Sending it again changes nothing and answers the same. Returns `replayed: true` then.
	 */
	function commit(number, body) {
		const { commitId, items } = validate(body);
		const key = normalize(number);
		const prescription = prescriptions.get(key);
		if (!prescription) throw new GovError(404, `Prescription ${key} not found`);

		const earlier = commits.get(commitId);
		if (earlier) {
			if (earlier.number !== key || !sameItems(earlier.items, items)) {
				throw new GovError(409, `Commit ${commitId} was already used for something else`);
			}
			return { commit: earlier, replayed: true };
		}

		for (const { productId, quantity } of items) {
			const line = prescription.find((l) => l.productId === productId);
			if (!line) throw new GovError(409, `${productId} is not on prescription ${key}`);
			const left = line.prescribed - line.used;
			if (quantity > left) {
				throw new GovError(409, `${productId}: asked to use ${quantity}, but only ${left} left on prescription ${key}`);
			}
			// Without a maximum the quantity is what the doctor prescribed: used whole, never in part.
			if (!line.maximum && quantity !== left) {
				throw new GovError(409, `${productId}: prescribed as ${left} and taken as it is, so ${quantity} cannot be used on prescription ${key}`);
			}
		}
		for (const { productId, quantity } of items) {
			prescription.find((l) => l.productId === productId).used += quantity;
		}
		const stored = { commitId, number: key, items, committedAt: new Date().toISOString() };
		commits.set(commitId, stored);
		return { commit: stored, replayed: false };
	}

	function commitsOf(number) {
		const key = normalize(number);
		if (!prescriptions.has(key)) return null;
		return [...commits.values()].filter((c) => c.number === key);
	}

	return { find, commit, commitsOf, reset };
}

/** Numbers are typed by hand at the counter: ignore case and stray spaces. */
function normalize(number) {
	return String(number).trim().toUpperCase();
}

function validate(body) {
	if (typeof body !== 'object' || body === null) throw new GovError(400, 'Expected a JSON object');
	const { commitId, items } = body;
	if (typeof commitId !== 'string' || commitId.trim() === '') throw new GovError(400, 'commitId is required');
	if (!Array.isArray(items) || items.length === 0) throw new GovError(400, 'items must list at least one product');

	const seen = new Set();
	const valid = items.map((item) => {
		if (typeof item?.productId !== 'string' || item.productId === '') throw new GovError(400, 'Every item needs a productId');
		if (!Number.isInteger(item.quantity) || item.quantity < 1) {
			throw new GovError(400, `${item.productId}: quantity must be a whole number, at least 1`);
		}
		if (seen.has(item.productId)) throw new GovError(400, `${item.productId} is listed twice`);
		seen.add(item.productId);
		return { productId: item.productId, quantity: item.quantity };
	});
	return { commitId: commitId.trim(), items: valid };
}

function sameItems(a, b) {
	const key = (items) => items.map((i) => `${i.productId}:${i.quantity}`).sort().join(',');
	return key(a) === key(b);
}
