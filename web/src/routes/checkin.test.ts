// @vitest-environment jsdom
import { cleanup, render, screen, waitFor } from '@testing-library/svelte';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { SAMPLE_PRESCRIPTIONS } from '#lib/government.ts';
import { fakeBackend, json } from '../test/backend.ts';
import Page from './+page.svelte';

const goto = vi.hoisted(() => vi.fn());
vi.mock('$app/navigation', () => ({ goto }));

const catalog = [
	{ id: 'amoxicillin', name: 'Amoxicillin 500 mg', category: 'Antibiotic', stock: 40 },
	{ id: 'atorvastatin', name: 'Atorvastatin 20 mg', category: 'Cholesterol', stock: 3 },
	{ id: 'ibuprofen', name: 'Ibuprofen 400 mg', category: 'Pain relief', stock: 60 },
	{ id: 'salbutamol', name: 'Salbutamol inhaler', category: 'Asthma', stock: 0 }
];

const government = {
	'GOV-1001': { number: 'GOV-1001', items: [{ medicationId: 'amoxicillin', quantity: 1, maximum: false }, { medicationId: 'ibuprofen', quantity: 2, maximum: true }] },
	'GOV-1003': { number: 'GOV-1003', items: [{ medicationId: 'atorvastatin', quantity: 5, maximum: true }, { medicationId: 'cetirizine', quantity: 1, maximum: false }] },
	'GOV-1005': { number: 'GOV-1005', items: [{ medicationId: 'cetirizine', quantity: 1, maximum: false }] }
} as const;

function backend() {
	return fakeBackend({
		'GET /api/medications': () => json(200, catalog),
		'GET /api/government-prescriptions/*': ({ path }) => {
			// Like the real one: the number is not case sensitive.
			const found = government[decodeURIComponent(path.split('/').pop()!).toUpperCase() as keyof typeof government];
			return found ? json(200, found) : json(404, { error: 'We could not find a government prescription with number NOPE. Please check the number.' });
		},
		'POST /api/prescriptions': () => json(201, { id: 'ticket-id', ticketNumber: 17 })
	});
}

let user: ReturnType<typeof userEvent.setup>;

beforeEach(() => {
	user = userEvent.setup();
	localStorage.clear();
	goto.mockReset();
});

afterEach(() => {
	cleanup();
	vi.unstubAllGlobals();
});

async function open() {
	render(Page);
	await screen.findByText('Amoxicillin 500 mg');
}

const more = (medication: string) => screen.getByRole('button', { name: `One pack more of ${medication}` });
const less = (medication: string) => screen.getByRole('button', { name: `One pack less of ${medication}` });

async function lookUp(number: string) {
	await user.type(screen.getByLabelText(/Government prescription number/), number);
	await user.click(screen.getByRole('button', { name: 'Look up' }));
}

describe('check-in by hand', () => {
	it('lists the medication with how much is in stock, and cannot ask for more than that', async () => {
		backend();
		await open();

		expect(screen.getByText(/Only 3 left/)).toBeInTheDocument();
		expect(screen.getByText(/Out of stock: ask the pharmacist for an alternative/)).toBeInTheDocument();
		for (let i = 0; i < 5; i++) await user.click(more('Atorvastatin 20 mg'));
		expect(screen.getByText(/1 medication, 3 packs/)).toBeInTheDocument();
		expect(more('Atorvastatin 20 mg')).toBeDisabled();
		expect(more('Salbutamol inhaler')).toBeDisabled();
		expect(less('Ibuprofen 400 mg')).toBeDisabled();
		for (let i = 0; i < 12; i++) await user.click(more('Ibuprofen 400 mg'));
		expect(screen.getByText(/2 medications, 15 packs/)).toBeInTheDocument(); // 3 atorvastatin, 12 ibuprofen: no limit but the shelf
		expect(more('Ibuprofen 400 mg')).toBeEnabled(); // 60 on the shelf
	});

	it('sends the check-in and takes the patient to their ticket', async () => {
		const server = backend();
		await open();

		await user.type(screen.getByLabelText('Your name'), 'Maria Silva');
		await user.click(more('Ibuprofen 400 mg'));
		await user.click(more('Ibuprofen 400 mg'));
		await user.click(more('Amoxicillin 500 mg'));
		await user.click(screen.getByRole('button', { name: 'Get my number' }));

		await waitFor(() => expect(goto).toHaveBeenCalledWith('/track/ticket-id'));
		const [post] = server.to('POST /api/prescriptions');
		expect(post.body).toMatchObject({
			patientName: 'Maria Silva',
			items: [
				{ medicationId: 'amoxicillin', quantity: 1 },
				{ medicationId: 'ibuprofen', quantity: 2 }
			]
		});
		expect(post.body).not.toHaveProperty('governmentNumber');
		expect((post.body as { id: string }).id).toMatch(/^[0-9a-f-]{36}$/);
		expect(JSON.parse(localStorage.getItem('pharmacy.activeTicket')!)).toEqual({ id: 'ticket-id', ticketNumber: 17 });
	});

	it('asks for a medication before it asks the backend anything', async () => {
		const server = backend();
		await open();

		await user.type(screen.getByLabelText('Your name'), 'Maria');
		await user.click(screen.getByRole('button', { name: 'Get my number' }));

		expect(await screen.findByRole('alert')).toHaveTextContent('Please choose at least one medication from your prescription.');
		expect(server.to('POST /api/prescriptions')).toHaveLength(0);
	});

	it('does not accept a name made of spaces', async () => {
		const server = backend();
		await open();

		await user.type(screen.getByLabelText('Your name'), '   ');
		await user.click(more('Ibuprofen 400 mg'));
		await user.click(screen.getByRole('button', { name: 'Get my number' }));

		expect(screen.getByLabelText('Your name')).toBeInvalid();
		expect((screen.getByLabelText('Your name') as HTMLInputElement).validationMessage).toBe('Please tell us your name.');
		expect(server.to('POST /api/prescriptions')).toHaveLength(0);
	});

	it('shows what the backend says when it refuses, and lets the patient try again', async () => {
		const server = backend();
		server.on('POST /api/prescriptions', () => json(400, { error: 'Quantity of Ibuprofen 400 mg must be at least 1' }));
		await open();
		await user.type(screen.getByLabelText('Your name'), 'Maria');
		await user.click(more('Ibuprofen 400 mg'));

		await user.click(screen.getByRole('button', { name: 'Get my number' }));

		expect(await screen.findByRole('alert')).toHaveTextContent('Quantity of Ibuprofen 400 mg must be at least 1');
		expect(screen.getByRole('button', { name: 'Get my number' })).toBeEnabled();
		expect(goto).not.toHaveBeenCalled();
	});

	describe('a check-in sent again', () => {
		async function failOnce() {
			const server = backend();
			server.on('POST /api/prescriptions', () => json(500, { error: 'Something went wrong on our side. Please try again.' }));
			await open();
			await user.type(screen.getByLabelText('Your name'), 'Maria');
			await user.click(more('Ibuprofen 400 mg'));
			await user.click(screen.getByRole('button', { name: 'Get my number' }));
			await screen.findByRole('alert');
			return server;
		}

		it('keeps its id, so that the patient never gets two tickets', async () => {
			const server = await failOnce();

			await user.click(screen.getByRole('button', { name: 'Get my number' }));
			await waitFor(() => expect(server.to('POST /api/prescriptions')).toHaveLength(2));

			const [first, second] = server.to('POST /api/prescriptions').map((call) => (call.body as { id: string }).id);
			expect(second).toBe(first);
		});

		it('gets a new id when the patient changed their mind in between', async () => {
			const server = await failOnce();

			await user.click(more('Ibuprofen 400 mg'));
			await user.click(screen.getByRole('button', { name: 'Get my number' }));
			await waitFor(() => expect(server.to('POST /api/prescriptions')).toHaveLength(2));

			const [first, second] = server.to('POST /api/prescriptions').map((call) => (call.body as { id: string }).id);
			expect(second).not.toBe(first);
		});
	});

	it('says so when the medication cannot be loaded, and loads it when asked to try again', async () => {
		const server = backend();
		server.on('GET /api/medications', () => json(500, { error: 'The pharmacy system is unavailable for a moment.' }));
		render(Page);

		expect(await screen.findByRole('alert')).toHaveTextContent('The pharmacy system is unavailable for a moment. We\'ll keep trying.');

		server.on('GET /api/medications', () => json(200, catalog));
		await user.click(screen.getByRole('button', { name: 'Try now' }));

		expect(await screen.findByText('Amoxicillin 500 mg')).toBeInTheDocument();
		expect(screen.queryByRole('alert')).not.toBeInTheDocument();
	});
});

describe('stock that changes while the patient is choosing', () => {
	// Only the timers the page waits on are fake: testing-library's waitFor does not cope with faked ones under vitest.
	beforeEach(() => {
		vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
		user = userEvent.setup({ delay: null });
	});
	afterEach(() => vi.useRealTimers());

	/** The catalog with other stock on the shelf. */
	const shelf = (stock: Record<string, number>) => catalog.map((m) => ({ ...m, stock: stock[m.id] ?? m.stock }));
	/** The next read of the shelf is due 5 to 7 seconds after the last one. */
	const nextRead = () => vi.advanceTimersByTime(7000);

	it('offers the packs that went back on the shelf (a number that did not answer) without a reload', async () => {
		const server = backend();
		await open();
		expect(screen.getByText(/Out of stock: ask the pharmacist for an alternative/)).toBeInTheDocument();
		expect(more('Salbutamol inhaler')).toBeDisabled();

		server.on('GET /api/medications', () => json(200, shelf({ salbutamol: 4 })));
		nextRead();

		expect(await screen.findByText(/Only 4 left/)).toBeInTheDocument();
		expect(screen.queryByText(/Out of stock/)).not.toBeInTheDocument();
		for (let i = 0; i < 6; i++) await user.click(more('Salbutamol inhaler'));
		expect(screen.getByText(/1 medication, 4 packs/)).toBeInTheDocument();
		expect(more('Salbutamol inhaler')).toBeDisabled();
	});

	it('keeps what the patient chose, but never more than the shelf holds now', async () => {
		const server = backend();
		await open();
		for (let i = 0; i < 3; i++) await user.click(more('Atorvastatin 20 mg'));
		for (let i = 0; i < 2; i++) await user.click(more('Ibuprofen 400 mg'));
		expect(screen.getByText(/2 medications, 5 packs/)).toBeInTheDocument();

		server.on('GET /api/medications', () => json(200, shelf({ atorvastatin: 1 })));
		nextRead();

		expect(await screen.findByText(/2 medications, 3 packs/)).toBeInTheDocument(); // 1 atorvastatin, 2 ibuprofen
		expect(screen.getByText(/Only 1 left/)).toBeInTheDocument();
		expect(more('Atorvastatin 20 mg')).toBeDisabled();
	});

	it('follows the stock of a government prescription too', async () => {
		const server = backend();
		await open();
		await lookUp('GOV-1003');
		expect(await screen.findByText(/1 medication, 3 packs/)).toBeInTheDocument(); // all 3 atorvastatin on the shelf
		expect(more('Atorvastatin 20 mg')).toBeDisabled();

		server.on('GET /api/medications', () => json(200, shelf({ atorvastatin: 8 })));
		nextRead();

		await waitFor(() => expect(more('Atorvastatin 20 mg')).toBeEnabled());
		for (let i = 0; i < 4; i++) await user.click(more('Atorvastatin 20 mg'));
		expect(screen.getByText(/1 medication, 5 packs/)).toBeInTheDocument(); // up to what the prescription allows
		expect(more('Atorvastatin 20 mg')).toBeDisabled();
	});

	it('reads the shelf again as soon as the patient comes back to the page', async () => {
		const server = backend();
		await open();

		server.on('GET /api/medications', () => json(200, shelf({ salbutamol: 4 })));
		document.dispatchEvent(new Event('visibilitychange'));

		expect(await screen.findByText(/Only 4 left/)).toBeInTheDocument();
	});

	it('keeps the last list on screen when a read fails, and carries on after it', async () => {
		const server = backend();
		await open();
		let reads = 0;
		let shownWhileFailing = false;
		server.on('GET /api/medications', () => {
			reads++;
			// By the second read the first one's failure has been dealt with.
			if (reads === 2) shownWhileFailing = screen.queryByText('Amoxicillin 500 mg') !== null && screen.queryByRole('alert') === null;
			return reads === 1 ? json(500, { error: 'The pharmacy system is unavailable for a moment.' }) : json(200, shelf({ salbutamol: 4 }));
		});

		// Time passes one read at a time (not inside waitFor: its own timeout runs on the same clock), until the failed
		// read has been followed by another.
		for (let i = 0; i < 20 && reads < 2; i++) {
			nextRead();
			await new Promise((resolve) => setImmediate(resolve));
		}

		expect(await screen.findByText(/Only 4 left/)).toBeInTheDocument();
		expect(shownWhileFailing).toBe(true);
	});

	it('stops reading once the patient has left the page', async () => {
		const server = backend();
		await open();
		cleanup();
		const reads = server.to('GET /api/medications').length;

		nextRead();
		nextRead();
		document.dispatchEvent(new Event('visibilitychange'));

		expect(server.to('GET /api/medications')).toHaveLength(reads);
	});
});

describe('a ticket from an earlier visit', () => {
	beforeEach(() => localStorage.setItem('pharmacy.activeTicket', JSON.stringify({ id: 'old-ticket', ticketNumber: 9 })));

	const tracking = (status: string) => json(200, { id: 'old-ticket', ticketNumber: 9, status });

	it('is offered while it is still going', async () => {
		const server = backend();
		server.on('GET /api/prescriptions/*', () => tracking('AWAITING_PACKAGING'));
		await open();

		expect(screen.getByText(/You already have number/)).toHaveTextContent('number 9 in progress');
		expect(screen.getByRole('link', { name: 'See its status' })).toHaveAttribute('href', '/track/old-ticket');
		await waitFor(() => expect(server.to('GET /api/prescriptions/*')).toHaveLength(1));
		expect(screen.getByText(/You already have number/)).toBeInTheDocument();
	});

	it.each([['collected'], ['rejected'], ['no show']])('is forgotten once it is over (%s)', async (_how) => {
		const server = backend();
		const status = { collected: 'COLLECTED', rejected: 'REJECTED', 'no show': 'NO_SHOW' }[_how]!;
		server.on('GET /api/prescriptions/*', () => tracking(status));
		await open();

		await waitFor(() => expect(screen.queryByText(/You already have number/)).not.toBeInTheDocument());
		expect(localStorage.getItem('pharmacy.activeTicket')).toBeNull();
	});

	it('is forgotten when the backend does not know it', async () => {
		const server = backend();
		server.on('GET /api/prescriptions/*', () => json(404, { error: 'not found' }));
		await open();

		await waitFor(() => expect(screen.queryByText(/You already have number/)).not.toBeInTheDocument());
	});

	it('is kept when the backend cannot be asked right now', async () => {
		const server = backend();
		server.on('GET /api/prescriptions/*', () => json(500, { error: 'oops' }));
		await open();

		await waitFor(() => expect(server.to('GET /api/prescriptions/*')).toHaveLength(1));
		expect(screen.getByText(/You already have number/)).toBeInTheDocument();
		expect(localStorage.getItem('pharmacy.activeTicket')).not.toBeNull();
	});
});

describe('check-in with a government prescription', () => {
	it('fills in the medication of the prescription, chosen up to what can be taken', async () => {
		backend();
		await open();

		await lookUp('GOV-1001');

		expect(await screen.findByText(/Medication on government prescription GOV-1001/)).toBeInTheDocument();
		expect(screen.getByText(/up to 2 packs/)).toBeInTheDocument();
		expect(screen.getByText(/1 pack, as prescribed/)).toBeInTheDocument();
		expect(screen.getByLabelText('Include Amoxicillin 500 mg')).toBeChecked();
		expect(screen.getByText(/2 medications, 3 packs/)).toBeInTheDocument();
		expect(screen.queryByText('Atorvastatin 20 mg')).not.toBeInTheDocument(); // not on it
	});

	it('sends the number with the check-in, as the patient edited it', async () => {
		const server = backend();
		await open();
		await lookUp('gov-1001');
		await screen.findByText(/Medication on government prescription/);

		await user.type(screen.getByLabelText('Your name'), 'Maria');
		await user.click(less('Ibuprofen 400 mg'));
		await user.click(screen.getByLabelText('Include Amoxicillin 500 mg')); // takes it out
		await user.click(screen.getByRole('button', { name: 'Get my number' }));

		await waitFor(() => expect(goto).toHaveBeenCalled());
		expect(server.to('GET /api/government-prescriptions/*')[0].path).toBe('/api/government-prescriptions/gov-1001');
		expect(server.to('POST /api/prescriptions')[0].body).toMatchObject({
			governmentNumber: 'GOV-1001',
			items: [{ medicationId: 'ibuprofen', quantity: 1 }]
		});
	});

	it('stops at what the pharmacy has in stock, and says what it does not carry at all', async () => {
		backend();
		await open();

		await lookUp('GOV-1003');

		expect(await screen.findByText(/up to 5 packs/)).toBeInTheDocument();
		expect(screen.getByText(/1 medication, 3 packs/)).toBeInTheDocument(); // 3 atorvastatin in stock
		expect(more('Atorvastatin 20 mg')).toBeDisabled();
		expect(screen.getByText(/Not stocked here, please ask a pharmacist: cetirizine\./)).toBeInTheDocument();
	});

	it('says so when the pharmacy carries none of it, and keeps the form for typing by hand', async () => {
		backend();
		await open();

		await lookUp('GOV-1005');

		expect(await screen.findByRole('alert')).toHaveTextContent('We do not stock any of the medication on this prescription. Please ask a pharmacist for help.');
		expect(screen.queryByText(/Medication on government prescription/)).not.toBeInTheDocument();
		expect(more('Ibuprofen 400 mg')).toBeEnabled();
	});

	it('shows the backend\'s answer for a number it does not know', async () => {
		backend();
		await open();

		await lookUp('NOPE');

		expect(await screen.findByRole('alert')).toHaveTextContent('We could not find a government prescription with number NOPE.');
	});

	it('needs a number before it looks anything up', async () => {
		backend();
		await open();

		expect(screen.getByRole('button', { name: 'Look up' })).toBeDisabled();
		await user.type(screen.getByLabelText(/Government prescription number/), '   ');
		expect(screen.getByRole('button', { name: 'Look up' })).toBeDisabled();
	});

	describe('the sample prescriptions', () => {
		const sample = (number: string) => screen.getByRole('button', { name: new RegExp(number) });
		const field = () => screen.getByLabelText(/Government prescription number/);

		it('are listed, each with what to expect from it', async () => {
			backend();
			await open();

			for (const { number, explanation } of SAMPLE_PRESCRIPTIONS) {
				expect(sample(number)).toHaveTextContent(explanation);
			}
			expect(screen.getByRole('group', { name: /Tap a sample/ })).toBeInTheDocument();
			expect(screen.getByText(/fewer than 5 atorvastatin on the shelf/)).toBeInTheDocument();
			expect(screen.getByText(/already collected/)).toBeInTheDocument();
		});

		it('fill in the number when tapped, and ask the government system nothing yet', async () => {
			const server = backend();
			await open();

			await user.click(sample('GOV-1002'));

			expect(field()).toHaveValue('GOV-1002');
			expect(screen.getByRole('button', { name: 'Look up' })).toBeEnabled();
			expect(server.to('GET /api/government-prescriptions/*')).toHaveLength(0);
		});

		it('replace what was typed, and take back an earlier answer of "not found"', async () => {
			backend();
			await open();
			await lookUp('NOPE');
			expect(await screen.findByRole('alert')).toHaveTextContent('We could not find');

			await user.click(sample('GOV-1001'));

			expect(field()).toHaveValue('GOV-1001');
			expect(screen.queryByRole('alert')).not.toBeInTheDocument();
		});

		it('lead to the prescription once looked up, and are gone while it is shown', async () => {
			backend();
			await open();

			await user.click(sample('GOV-1001'));
			await user.click(screen.getByRole('button', { name: 'Look up' }));

			expect(await screen.findByText(/Medication on government prescription GOV-1001/)).toBeInTheDocument();
			expect(screen.queryByRole('group', { name: /Tap a sample/ })).not.toBeInTheDocument();

			await user.click(screen.getByRole('button', { name: 'Enter my medication by hand instead' }));
			expect(sample('GOV-1001')).toBeInTheDocument(); // back to the start
		});
	});

	it('goes back to typing by hand, with nothing chosen', async () => {
		backend();
		await open();
		await lookUp('GOV-1001');
		await screen.findByText(/Medication on government prescription/);

		await user.click(screen.getByRole('button', { name: 'Enter my medication by hand instead' }));

		expect(screen.getByText('Medication on your prescription')).toBeInTheDocument();
		expect(screen.getByText(/Nothing selected yet/)).toBeInTheDocument();
		expect(screen.getByLabelText(/Government prescription number/)).toBeInTheDocument();
	});
});
