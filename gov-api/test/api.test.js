import assert from 'node:assert/strict';
import { after, before, beforeEach, test } from 'node:test';
import { createApp } from '../src/app.js';
import { createStore } from '../src/store.js';

let server;
let base;

before(async () => {
	server = createApp();
	await new Promise((resolve) => server.listen(0, resolve));
	base = `http://localhost:${server.address().port}`;
});

after(() => server.close());

beforeEach(() => call('POST', '/admin/reset'));

async function call(method, path, body) {
	const response = await fetch(base + path, {
		method,
		headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
		body: body === undefined ? undefined : typeof body === 'string' ? body : JSON.stringify(body)
	});
	return { status: response.status, body: await response.json() };
}

const commit = (number, commitId, ...items) =>
	call('POST', `/prescriptions/${number}/commits`, {
		commitId,
		items: items.map(([productId, quantity]) => ({ productId, quantity }))
	});

test('returns the prescription: product, quantity and whether the quantity is a maximum', async () => {
	const { status, body } = await call('GET', '/prescriptions/GOV-1001');

	assert.equal(status, 200);
	assert.deepEqual(body, {
		number: 'GOV-1001',
		items: [
			{ productId: 'amoxicillin', quantity: 1, maximum: false },
			{ productId: 'ibuprofen', quantity: 2, maximum: true }
		]
	});
});

test('numbers typed by hand match whatever the case', async () => {
	const { status, body } = await call('GET', '/prescriptions/gov-1001');

	assert.equal(status, 200);
	assert.equal(body.number, 'GOV-1001');
});

test('an unknown number is a 404', async () => {
	const { status, body } = await call('GET', '/prescriptions/NOPE');

	assert.equal(status, 404);
	assert.equal(body.error, 'Prescription NOPE not found');
});

test('with a maximum of 2, committing 1 uses 1 and leaves 1', async () => {
	const { status, body } = await commit('GOV-1001', 'c-1', ['ibuprofen', 1]);

	assert.equal(status, 201);
	assert.deepEqual(body.items, [{ productId: 'ibuprofen', quantity: 1 }]);
	const left = (await call('GET', '/prescriptions/GOV-1001')).body.items;
	assert.deepEqual(left.find((i) => i.productId === 'ibuprofen'), { productId: 'ibuprofen', quantity: 1, maximum: true });
	assert.equal(left.find((i) => i.productId === 'amoxicillin').quantity, 1, 'other products are untouched');
});

test('what is used stays used: a second commit cannot go over the maximum', async () => {
	await commit('GOV-1001', 'c-1', ['ibuprofen', 1]);

	const over = await commit('GOV-1001', 'c-2', ['ibuprofen', 2]);

	assert.equal(over.status, 409);
	assert.match(over.body.error, /only 1 left/);
	assert.equal((await commit('GOV-1001', 'c-3', ['ibuprofen', 1])).status, 201);
});

test('the same commit sent again changes nothing and answers the same', async () => {
	const first = await commit('GOV-1001', 'c-1', ['ibuprofen', 1]);
	const again = await commit('GOV-1001', 'c-1', ['ibuprofen', 1]);

	assert.equal(first.status, 201);
	assert.equal(again.status, 200);
	assert.deepEqual(again.body, first.body);
	const ibuprofen = (await call('GET', '/prescriptions/GOV-1001')).body.items.find((i) => i.productId === 'ibuprofen');
	assert.equal(ibuprofen.quantity, 1, 'used once, not twice');
});

test('a commit id cannot be reused for something else', async () => {
	await commit('GOV-1001', 'c-1', ['ibuprofen', 1]);

	const other = await commit('GOV-1001', 'c-1', ['ibuprofen', 2]);

	assert.equal(other.status, 409);
});

test('a commit is all or nothing', async () => {
	const { status } = await commit('GOV-1001', 'c-1', ['amoxicillin', 1], ['ibuprofen', 3]);

	assert.equal(status, 409);
	const items = (await call('GET', '/prescriptions/GOV-1001')).body.items;
	assert.deepEqual(items.map((i) => i.quantity), [1, 2], 'the amoxicillin was not used either');
});

test('a product that is not on the prescription is refused', async () => {
	const { status, body } = await commit('GOV-1001', 'c-1', ['metformin', 1]);

	assert.equal(status, 409);
	assert.match(body.error, /metformin is not on prescription GOV-1001/);
});

test('a prescription used in full has nothing left, and cannot be used again', async () => {
	const { body } = await call('GET', '/prescriptions/GOV-1004');
	assert.deepEqual(body.items, [{ productId: 'omeprazole', quantity: 0, maximum: false }]);

	assert.equal((await commit('GOV-1004', 'c-1', ['omeprazole', 1])).status, 409);
});

test('commits to an unknown prescription are a 404', async () => {
	assert.equal((await commit('NOPE', 'c-1', ['ibuprofen', 1])).status, 404);
});

test('malformed commits are a 400', async () => {
	for (const body of [
		'not json',
		{},
		{ commitId: 'c-1' },
		{ commitId: 'c-1', items: [] },
		{ commitId: 'c-1', items: [{ productId: 'ibuprofen', quantity: 0 }] },
		{ commitId: 'c-1', items: [{ productId: 'ibuprofen', quantity: 1.5 }] },
		{ commitId: 'c-1', items: [{ quantity: 1 }] },
		{ commitId: 'c-1', items: [{ productId: 'ibuprofen', quantity: 1 }, { productId: 'ibuprofen', quantity: 1 }] }
	]) {
		const response = await call('POST', '/prescriptions/GOV-1001/commits', body);
		assert.equal(response.status, 400, JSON.stringify(body));
	}
});

test('lists the commits received for a prescription', async () => {
	await commit('GOV-1001', 'c-1', ['ibuprofen', 1]);
	await commit('GOV-1002', 'c-2', ['metformin', 1]);

	const { status, body } = await call('GET', '/prescriptions/GOV-1001/commits');

	assert.equal(status, 200);
	assert.deepEqual(body.map((c) => c.commitId), ['c-1']);
});

test('unknown paths are 404 and wrong methods 405', async () => {
	assert.equal((await call('GET', '/nope')).status, 404);
	assert.equal((await call('DELETE', '/prescriptions/GOV-1001')).status, 405);
});

test('a bad % escape in the number is a 400, not a crash', async () => {
	for (const [method, path] of [
		['GET', '/prescriptions/%E0%A4%A'],
		['GET', '/prescriptions/%ZZ/commits'],
		['POST', '/prescriptions/%/commits']
	]) {
		const { status, body } = await call(method, path, method === 'POST' ? {} : undefined);
		assert.equal(status, 400, `${method} ${path}`);
		assert.match(body.error, /Malformed path/);
	}
});

test('a product prescribed without a maximum is used whole, never in part', async () => {
	const app = createApp({
		store: createStore({ 'GOV-9': [['amoxicillin', 3, false, 0], ['ibuprofen', 3, true, 0]] })
	});
	await new Promise((resolve) => app.listen(0, resolve));
	const own = `http://localhost:${app.address().port}/prescriptions/GOV-9/commits`;
	const post = async (commitId, productId, quantity) => {
		const response = await fetch(own, {
			method: 'POST',
			headers: { 'Content-Type': 'application/json' },
			body: JSON.stringify({ commitId, items: [{ productId, quantity }] })
		});
		return { status: response.status, body: await response.json() };
	};
	try {
		const part = await post('c-1', 'amoxicillin', 2);
		assert.equal(part.status, 409);
		assert.match(part.body.error, /prescribed as 3 and taken as it is/);

		assert.equal((await post('c-2', 'ibuprofen', 2)).status, 201, 'a maximum can be used in part');
		assert.equal((await post('c-3', 'amoxicillin', 3)).status, 201, 'the whole of the other');
		assert.equal((await post('c-4', 'amoxicillin', 3)).status, 409, 'and not twice');
	} finally {
		app.close();
	}
});
