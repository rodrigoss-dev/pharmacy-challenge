// @vitest-environment jsdom
import { cleanup, render, screen, waitFor, within } from '@testing-library/svelte';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fakeBackend, json } from '../../test/backend.ts';
import Page from './+page.svelte';

const now = new Date().toISOString();
const minutesAgo = (minutes: number) => new Date(Date.now() - minutes * 60_000).toISOString();

const prescription = (id: string, ticketNumber: number, status: string, over: Record<string, unknown> = {}) => ({
	id,
	ticketNumber,
	patientName: `Patient ${ticketNumber}`,
	status,
	statusReason: null,
	items: [{ medicationId: 'ibuprofen', name: 'Ibuprofen 400 mg', quantity: 2 }],
	checkedInAt: minutesAgo(10),
	updatedAt: minutesAgo(3),
	governmentNumber: null,
	...over
});

const healthy = {
	database: 'up',
	broker: 'up',
	brokerBlocked: null,
	pendingEvents: 0,
	oldestPendingEventSeconds: null,
	stalledStockChecks: 0,
	deadLetters: 0,
	pendingGovernmentCommits: 0,
	oldestPendingGovernmentCommitSeconds: null,
	rejectedGovernmentCommits: 0,
	liveStreams: 2
};

const queues = {
	stockCheck: [prescription('p1', 1, 'RECEIVED', { updatedAt: now })],
	pharmacist: [prescription('p2', 2, 'AWAITING_APPROVAL', { governmentNumber: 'GOV-1001' })],
	packaging: [prescription('p3', 3, 'AWAITING_PACKAGING')],
	pickup: [prescription('p4', 4, 'READY_FOR_PICKUP'), prescription('p5', 5, 'CALLED')]
};

const medications = [
	{ id: 'ibuprofen', name: 'Ibuprofen 400 mg', category: 'Pain relief', stock: 12 },
	{ id: 'salbutamol', name: 'Salbutamol inhaler', category: 'Asthma', stock: 0 }
];

function backend(over: { health?: Record<string, unknown> } = {}) {
	return fakeBackend({
		'GET /api/staff/queues': () => json(200, queues),
		'GET /api/medications': () => json(200, medications),
		'GET /health': () => json(200, { ...healthy, ...over.health }),
		'POST /api/staff/prescriptions/*/*': () => json(200, {}),
		'POST /api/staff/medications/*/restock': () => json(200, {})
	});
}

let user: ReturnType<typeof userEvent.setup>;

beforeEach(() => {
	user = userEvent.setup();
});

afterEach(() => {
	cleanup();
	vi.unstubAllGlobals();
});

const queue = (title: RegExp) => within(screen.getByRole('heading', { name: title }).closest('section')!);

async function open(over: Parameters<typeof backend>[0] = {}) {
	const server = backend(over);
	render(Page);
	await screen.findByText('Patient 2');
	return server;
}

describe('the queues', () => {
	it('shows who is waiting where, with the names the patients gave and how long they have been there', async () => {
		await open();

		expect(queue(/Stock check/).getByText('Patient 1')).toBeInTheDocument();
		expect(queue(/approval queue/).getByText('Patient 2')).toBeInTheDocument();
		expect(queue(/approval queue/).getByText('government prescription GOV-1001')).toBeInTheDocument();
		expect(queue(/Packaging queue/).getByText('Patient 3')).toBeInTheDocument();
		expect(queue(/pickup counter/).getByText('Patient 4')).toBeInTheDocument();
		expect(queue(/approval queue/).getByText('3 min in this step')).toBeInTheDocument();
		expect(queue(/approval queue/).getByText('Ibuprofen 400 mg × 2')).toBeInTheDocument();
		expect(queue(/Stock check/).getByRole('heading')).toHaveTextContent('Stock check (1)');
	});

	it('offers each queue the step that comes next', async () => {
		await open();

		expect(queue(/Stock check/).getByRole('button', { name: 'Retry stock check' })).toBeInTheDocument();
		expect(queue(/approval queue/).getAllByRole('button').map((b) => b.textContent!.trim())).toEqual(['Approve', 'Reject...']);
		expect(queue(/Packaging queue/).getAllByRole('button').map((b) => b.textContent!.trim())).toEqual(['Packed']);
		const pickup = queue(/pickup counter/);
		expect(pickup.getByRole('button', { name: 'Call number 4' })).toBeInTheDocument();
		expect(pickup.getAllByRole('button').map((b) => b.textContent!.trim())).toEqual(['Call number 4', 'Call again', 'No show', 'Handed over']);
		expect(pickup.getByText('called')).toBeInTheDocument();
	});

	it('says when a queue is empty, and when nothing has been loaded yet', async () => {
		const server = fakeBackend({
			'GET /api/staff/queues': () => json(200, { stockCheck: [], pharmacist: [], packaging: [], pickup: [] }),
			'GET /api/medications': () => json(200, []),
			'GET /health': () => json(200, healthy)
		});
		render(Page);

		expect(screen.getAllByText('Not loaded yet').length).toBeGreaterThan(0);
		await waitFor(() => expect(screen.getAllByText('Empty')).toHaveLength(4));
		expect(server.to('GET /api/staff/queues')).toHaveLength(1);
	});

	it('keeps the queues up to date by itself', async () => {
		vi.useFakeTimers({ shouldAdvanceTime: true });
		try {
			const server = await open();
			expect(server.to('GET /api/staff/queues')).toHaveLength(1);

			await vi.advanceTimersByTimeAsync(2100);

			await waitFor(() => expect(server.to('GET /api/staff/queues')).toHaveLength(2));
		} finally {
			vi.useRealTimers();
		}
	});
});

describe('the steps', () => {
	it.each([
		['Approve', 'p2', 'approve'],
		['Packed', 'p3', 'packed'],
		['Call number 4', 'p4', 'call'],
		['Call again', 'p5', 'call'],
		['Handed over', 'p5', 'hand-over'],
		['Retry stock check', 'p1', 'retry-stock-check']
	])('%s sends %s to %s, and refreshes the queues', async (label, id, path) => {
		const server = await open();
		const before = server.to('GET /api/staff/queues').length;

		await user.click(screen.getByRole('button', { name: label }));

		await waitFor(() => expect(server.to(`POST /api/staff/prescriptions/${id}/${path}`)).toHaveLength(1));
		await waitFor(() => expect(server.to('GET /api/staff/queues').length).toBeGreaterThan(before));
		expect(server.to(`POST /api/staff/prescriptions/${id}/${path}`)[0].body).toBeUndefined();
	});

	describe('a rejection', () => {
		it('needs a reason, which the patient will read', async () => {
			const prompt = vi.fn((_message?: string) => 'Allergy on file');
			vi.stubGlobal('prompt', prompt);
			const server = await open();

			await user.click(screen.getByRole('button', { name: 'Reject...' }));

			await waitFor(() => expect(server.to('POST /api/staff/prescriptions/p2/reject')).toHaveLength(1));
			expect(server.to('POST /api/staff/prescriptions/p2/reject')[0].body).toEqual({ reason: 'Allergy on file' });
			expect(prompt.mock.calls[0][0]).toContain('The patient will see this');
			expect(prompt.mock.calls[0][0]).toContain('200 characters');
		});

		it.each([[null], ['']])('is dropped when the reason is %j', async (answer) => {
			vi.stubGlobal('prompt', vi.fn(() => answer));
			const server = await open();

			await user.click(screen.getByRole('button', { name: 'Reject...' }));

			expect(server.to('POST /api/staff/prescriptions/p2/reject')).toHaveLength(0);
		});
	});

	describe('a no show', () => {
		it('asks first, because the medication goes back on the shelf', async () => {
			const confirm = vi.fn((_message?: string) => true);
			vi.stubGlobal('confirm', confirm);
			const server = await open();

			await user.click(screen.getByRole('button', { name: 'No show' }));

			await waitFor(() => expect(server.to('POST /api/staff/prescriptions/p5/no-show')).toHaveLength(1));
			expect(confirm.mock.calls[0][0]).toContain('Number 5 did not answer?');
			expect(confirm.mock.calls[0][0]).toContain('goes back on the shelf');
		});

		it('is dropped if the pharmacist changes their mind', async () => {
			vi.stubGlobal('confirm', vi.fn(() => false));
			const server = await open();

			await user.click(screen.getByRole('button', { name: 'No show' }));

			expect(server.to('POST /api/staff/prescriptions/p5/no-show')).toHaveLength(0);
		});
	});

	it('shows why a step was refused, and goes on', async () => {
		const server = await open();
		server.on('POST /api/staff/prescriptions/*/approve', () =>
			json(409, { error: 'Prescription #2 is already waiting to be packed: someone else may have just done this' })
		);

		await user.click(screen.getByRole('button', { name: 'Approve' }));

		expect(await screen.findByRole('alert')).toHaveTextContent('Prescription #2 is already waiting to be packed');
		expect(screen.getByRole('button', { name: 'Approve' })).toBeEnabled();
		server.on('POST /api/staff/prescriptions/*/approve', () => json(200, {}));
		await user.click(screen.getByRole('button', { name: 'Approve' }));
		await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument());
	});

	it('cannot be pressed twice while it is on its way', async () => {
		const server = await open();
		let finish!: () => void;
		server.on('POST /api/staff/prescriptions/*/packed', () => new Promise<Response>((resolve) => (finish = () => resolve(json(200, {})))));

		await user.click(screen.getByRole('button', { name: 'Packed' }));

		expect(screen.getByRole('button', { name: 'Packed' })).toBeDisabled();
		expect(screen.getByRole('button', { name: 'Approve' })).toBeDisabled();
		finish();
		await waitFor(() => expect(screen.getByRole('button', { name: 'Packed' })).toBeEnabled());
		expect(server.to('POST /api/staff/prescriptions/p3/packed')).toHaveLength(1);
	});
});

describe('the inventory', () => {
	it('shows the stock, and flags what is out', async () => {
		await open();

		const table = within(screen.getByRole('table'));
		expect(table.getByRole('row', { name: /Ibuprofen 400 mg Pain relief 12/ })).toBeInTheDocument();
		expect(table.getByRole('row', { name: /Salbutamol inhaler Asthma 0 out of stock/ })).toBeInTheDocument();
	});

	it('adds ten packs to the medication of the row', async () => {
		const server = await open();

		await user.click(within(screen.getByRole('row', { name: /Salbutamol inhaler/ })).getByRole('button', { name: '+10 packs' }));

		await waitFor(() => expect(server.to('POST /api/staff/medications/salbutamol/restock')).toHaveLength(1));
		expect(server.to('POST /api/staff/medications/salbutamol/restock')[0].body).toEqual({ quantity: 10 });
	});
});

describe('warnings', () => {
	const banner = () => screen.queryByRole('status');

	it('says nothing while everything is fine', async () => {
		await open();

		expect(banner()).not.toBeInTheDocument();
	});

	it.each([
		['the database is down', { database: 'down' }, 'The database is unreachable: nothing can be saved right now'],
		['the broker is down', { broker: 'down' }, 'Message broker (RabbitMQ) is unreachable. Check-ins and the buttons below still work'],
		['the broker blocks publishers', { brokerBlocked: 'low on memory' }, 'RabbitMQ is refusing new messages (low on memory)'],
		['events pile up', { pendingEvents: 21, oldestPendingEventSeconds: 400 }, '21 events waiting to be delivered, the oldest for 6 min.'],
		['stock checks stall', { stalledStockChecks: 2 }, '2 stock checks taking longer than usual'],
		['one stock check stalls', { stalledStockChecks: 1 }, '1 stock check taking longer than usual'],
		['messages are parked', { deadLetters: 3 }, '3 stock check messages failed repeatedly and were parked'],
		['a government commit waits a minute', { pendingGovernmentCommits: 1, oldestPendingGovernmentCommitSeconds: 60 }, '1 commit to the government system waiting, the oldest for 60s.'],
		['government commits wait long', { pendingGovernmentCommits: 4, oldestPendingGovernmentCommitSeconds: 600 }, '4 commits to the government system waiting, the oldest for 10 min.'],
		['the government refuses a commit', { rejectedGovernmentCommits: 1 }, '1 commit to the government system refused for good.']
	])('says so when %s', async (_what, health, message) => {
		await open({ health });

		await waitFor(() => expect(banner()).toHaveTextContent(message));
	});

	it.each([
		['a few events wait', { pendingEvents: 20, oldestPendingEventSeconds: 3 }],
		['a government commit has waited seconds', { pendingGovernmentCommits: 1, oldestPendingGovernmentCommitSeconds: 59 }],
		['the health report has nothing to say about the database', { pendingEvents: null, stalledStockChecks: null, deadLetters: null }]
	])('stays quiet when %s', async (_what, health) => {
		await open({ health });

		await waitFor(() => expect(screen.getByText('Patient 2')).toBeInTheDocument());
		expect(banner()).not.toBeInTheDocument();
	});

	it('keeps the queues on screen when only the health check fails, and says what failed', async () => {
		const server = backend();
		server.on('GET /health', () => json(500, { error: 'Something went wrong on our side. Please try again.' }));
		render(Page);

		expect(await screen.findByText('Patient 2')).toBeInTheDocument();
		expect(await screen.findByRole('alert')).toHaveTextContent('Something went wrong on our side. Please try again. The console keeps trying.');
	});

	it('says when the backend cannot be reached at all', async () => {
		const server = backend();
		server.on('GET /api/staff/queues', () => json(503, { error: 'The pharmacy system is unavailable for a moment.' }));
		server.on('GET /api/medications', () => json(503, { error: 'The pharmacy system is unavailable for a moment.' }));
		render(Page);

		expect(await screen.findByRole('alert')).toHaveTextContent('The pharmacy system is unavailable for a moment. The console keeps trying.');
		expect(screen.getAllByText('Not loaded yet').length).toBeGreaterThan(0);
	});

	it('shows one banner, not two, when a step and the refresh fail the same way', async () => {
		const server = await open();
		const unavailable = () => json(503, { error: 'The pharmacy system is unavailable for a moment.' });
		server.on('POST /api/staff/prescriptions/*/approve', unavailable);
		server.on('GET /api/staff/queues', unavailable);

		await user.click(screen.getByRole('button', { name: 'Approve' }));

		await waitFor(() => expect(screen.getAllByRole('alert')).toHaveLength(1));
		expect(screen.getByRole('alert')).toHaveTextContent('The pharmacy system is unavailable for a moment. The console keeps trying.');
	});
});
