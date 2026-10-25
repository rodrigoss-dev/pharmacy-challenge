// @vitest-environment jsdom
import { cleanup, render, screen, waitFor } from '@testing-library/svelte';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fakeBackend, json } from '../../../test/backend.ts';
import { FakeEventSource } from '../../../test/fake-event-source.ts';
import Page from './+page.svelte';

vi.mock('$app/state', () => ({ page: { params: { id: 'abc' } } }));

const tracking = (over: Record<string, unknown> = {}) => ({
	id: 'abc',
	ticketNumber: 17,
	patientName: 'Maria Silva',
	status: 'AWAITING_APPROVAL',
	statusReason: null,
	items: [{ medicationId: 'ibuprofen', name: 'Ibuprofen 400 mg', quantity: 2 }],
	peopleAhead: 2,
	delayed: false,
	checkedInAt: '2026-10-25T10:00:00Z',
	updatedAt: '2026-10-25T10:00:00Z',
	...over
});

const vibrate = vi.fn();
let user: ReturnType<typeof userEvent.setup>;

beforeEach(() => {
	user = userEvent.setup();
	FakeEventSource.reset();
	vibrate.mockReset();
	localStorage.clear();
	vi.stubGlobal('EventSource', FakeEventSource);
	Object.defineProperty(navigator, 'vibrate', { value: vibrate, configurable: true });
});

afterEach(() => {
	cleanup();
	vi.unstubAllGlobals();
});

/** Opens the page for a prescription as the backend has it, and waits for the live stream to be connected. */
async function open(first = tracking()) {
	const server = fakeBackend({ 'GET /api/prescriptions/*': () => json(200, first) });
	render(Page);
	await waitFor(() => expect(FakeEventSource.all).toHaveLength(1));
	FakeEventSource.latest.open();
	return server;
}

const update = (over: Record<string, unknown>) => FakeEventSource.latest.send('status', tracking(over));

describe('the patient follows their prescription', () => {
	it('sees their number, their first name, what is happening and what they asked for', async () => {
		await open();

		expect(await screen.findByText('17')).toBeInTheDocument();
		expect(screen.getByText('Hi Maria')).toBeInTheDocument();
		expect(screen.getByRole('status')).toHaveTextContent('Waiting for a pharmacist');
		expect(screen.getByRole('status')).toHaveTextContent('2 people ahead of you.');
		expect(screen.getByText('Ibuprofen 400 mg')).toBeInTheDocument();
		expect(screen.getByText('2 packs')).toBeInTheDocument();
		expect(screen.getByText('Pharmacist review')).toBeInTheDocument();
		expect(document.title).toBe('Waiting for a pharmacist - number 17');
	});

	it('is told of every step as it happens, without reloading', async () => {
		await open();
		await screen.findByText('17');

		update({ status: 'AWAITING_PACKAGING', peopleAhead: 0 });
		expect(await screen.findByText('Approved, now packing')).toBeInTheDocument();
		expect(screen.getByRole('status')).toHaveTextContent("You're next.");

		update({ status: 'READY_FOR_PICKUP' });
		expect(await screen.findByText("Ready! We'll call you shortly")).toBeInTheDocument();
	});

	it('says the connection is live, delayed, or being re-established', async () => {
		await open();
		await screen.findByText('17');

		expect(screen.getByText('Live: this page updates by itself')).toBeInTheDocument();
		FakeEventSource.latest.send('unavailable', { error: 'later' });
		expect(await screen.findByText(/Updates are delayed\.\.\. your place in line is safe/)).toBeInTheDocument();
		update({});
		expect(await screen.findByText('Live: this page updates by itself')).toBeInTheDocument();
		FakeEventSource.latest.drop(false);
		expect(await screen.findByText(/Reconnecting\.\.\. your place in line is safe/)).toBeInTheDocument();
	});

	it('points to the counter, with the reason, when it cannot be filled', async () => {
		await open();
		await screen.findByText('17');

		update({ status: 'REJECTED', statusReason: 'Allergy on file' });

		expect(await screen.findByText('Please speak to a pharmacist')).toBeInTheDocument();
		expect(screen.getByRole('status')).toHaveTextContent('Allergy on file. Please come to the counter and mention number 17.');
		expect(screen.queryByLabelText('Progress')).not.toBeInTheDocument();
	});

	it('offers to check in again when nobody answered', async () => {
		await open();
		await screen.findByText('17');

		update({ status: 'NO_SHOW', statusReason: 'Nobody answered when number 17 was called' });

		expect(await screen.findByText("We couldn't reach you")).toBeInTheDocument();
		expect(screen.getByRole('link', { name: 'Start a new check-in' })).toHaveAttribute('href', '/');
	});

	it('hears about a delayed stock check', async () => {
		await open(tracking({ status: 'RECEIVED', delayed: true }));

		expect(await screen.findByText(/This is taking longer than usual/)).toBeInTheDocument();
	});
});

describe('when the number is called', () => {
	it('is told, in the page and the tab title, and the phone vibrates', async () => {
		await open();
		await screen.findByText('17');

		update({ status: 'CALLED', updatedAt: '2026-10-25T10:05:00Z' });

		expect(await screen.findByText("It's your turn!")).toBeInTheDocument();
		await waitFor(() => expect(document.title).toBe('Your turn! Number 17'));
		expect(vibrate).toHaveBeenCalledTimes(1);
	});

	it('is told again when the pharmacist calls again, but not for an update that changes nothing', async () => {
		await open();
		await screen.findByText('17');
		update({ status: 'CALLED', updatedAt: '2026-10-25T10:05:00Z' });
		await waitFor(() => expect(vibrate).toHaveBeenCalledTimes(1));

		update({ status: 'CALLED', updatedAt: '2026-10-25T10:05:00Z' });
		expect(vibrate).toHaveBeenCalledTimes(1);

		update({ status: 'CALLED', updatedAt: '2026-10-25T10:06:00Z' });
		await waitFor(() => expect(vibrate).toHaveBeenCalledTimes(2));
	});

	it('does not vibrate just because the page was opened on a called number', async () => {
		await open(tracking({ status: 'CALLED' }));

		expect(await screen.findByText("It's your turn!")).toBeInTheDocument();
		expect(vibrate).not.toHaveBeenCalled();
	});

	it('beeps only if the patient asked for sound, and can turn it off again', async () => {
		const oscillators: number[] = [];
		const context = {
			currentTime: 0,
			destination: {},
			resume: vi.fn(async () => {}),
			close: vi.fn(async () => {}),
			createOscillator: () => {
				oscillators.push(1);
				return { frequency: {}, connect: (next: unknown) => next, start: () => {}, stop: () => {} };
			},
			createGain: () => ({ gain: { setValueAtTime: () => {}, exponentialRampToValueAtTime: () => {} }, connect: (next: unknown) => next })
		};
		vi.stubGlobal('AudioContext', function () {
			return context;
		});
		await open();
		await screen.findByText('17');
		update({ status: 'CALLED', updatedAt: '2026-10-25T10:05:00Z' });
		await waitFor(() => expect(vibrate).toHaveBeenCalledTimes(1));
		expect(oscillators).toHaveLength(0);

		const button = screen.getByRole('button', { name: "Play a sound when it's my turn" });
		expect(button).toHaveAttribute('aria-pressed', 'false');
		await user.click(button);
		expect(screen.getByRole('button', { name: /Sound on/ })).toHaveAttribute('aria-pressed', 'true');
		expect(oscillators).toHaveLength(1); // the test beep

		update({ status: 'CALLED', updatedAt: '2026-10-25T10:06:00Z' });
		await waitFor(() => expect(oscillators).toHaveLength(4));

		await user.click(screen.getByRole('button', { name: /Sound on/ }));
		expect(context.close).toHaveBeenCalled();
		expect(screen.getByRole('button', { name: "Play a sound when it's my turn" })).toHaveAttribute('aria-pressed', 'false');
	});
});

describe('when it is over', () => {
	it('stops listening, forgets the ticket, and has nothing left to switch on', async () => {
		localStorage.setItem('pharmacy.activeTicket', JSON.stringify({ id: 'abc', ticketNumber: 17 }));
		await open();
		await screen.findByText('17');

		update({ status: 'COLLECTED' });

		expect(await screen.findByText('All done. Get well soon!')).toBeInTheDocument();
		expect(FakeEventSource.latest.closed).toBe(true);
		expect(localStorage.getItem('pharmacy.activeTicket')).toBeNull();
		expect(screen.queryByRole('button', { name: /sound/i })).not.toBeInTheDocument();
	});

	it.each(['COLLECTED', 'OUT_OF_STOCK', 'REJECTED', 'NO_SHOW'] as const)(
		'offers to start a new check-in, whichever way it ended (%s)',
		async (status) => {
			await open();
			await screen.findByText('17');
			expect(screen.queryByRole('link', { name: 'Start a new check-in' })).not.toBeInTheDocument(); // not while it goes on

			update({ status, statusReason: 'Not enough stock of Ibuprofen 400 mg' });

			expect(await screen.findByRole('link', { name: 'Start a new check-in' })).toHaveAttribute('href', '/');
		}
	);

	it('offers to start a new check-in when the page is opened on a prescription that was over already', async () => {
		fakeBackend({ 'GET /api/prescriptions/*': () => json(200, tracking({ status: 'COLLECTED' })) });
		render(Page);

		expect(await screen.findByRole('link', { name: 'Start a new check-in' })).toHaveAttribute('href', '/');
	});

	it('does not even connect for a prescription that was over already', async () => {
		fakeBackend({ 'GET /api/prescriptions/*': () => json(200, tracking({ status: 'COLLECTED' })) });
		render(Page);

		expect(await screen.findByText('All done. Get well soon!')).toBeInTheDocument();
		expect(FakeEventSource.all).toHaveLength(0);
	});
});

describe('when the ticket cannot be found', () => {
	it('says so, and forgets it', async () => {
		localStorage.setItem('pharmacy.activeTicket', JSON.stringify({ id: 'abc', ticketNumber: 17 }));
		fakeBackend({ 'GET /api/prescriptions/*': () => json(404, { error: 'Prescription abc not found' }) });
		render(Page);

		expect(await screen.findByText("We can't find this ticket")).toBeInTheDocument();
		expect(screen.getByRole('link', { name: 'Check in' })).toHaveAttribute('href', '/');
		expect(localStorage.getItem('pharmacy.activeTicket')).toBeNull();
		expect(FakeEventSource.all).toHaveLength(0);
	});

	it('says so when the live stream reports it, and hangs up', async () => {
		await open();
		await screen.findByText('17');

		FakeEventSource.latest.send('not-found', { error: 'Prescription abc not found' });

		expect(await screen.findByText("We can't find this ticket")).toBeInTheDocument();
		expect(FakeEventSource.latest.closed).toBe(true);
	});
});

describe('when the backend cannot be reached', () => {
	it('keeps trying, and shows the prescription as soon as the live stream delivers it', async () => {
		fakeBackend({ 'GET /api/prescriptions/*': () => json(500, { error: 'The pharmacy system is unavailable for a moment.' }) });
		render(Page);

		expect(await screen.findByText(/The pharmacy system is unavailable for a moment\. We'll keep trying\./)).toBeInTheDocument();
		await waitFor(() => expect(FakeEventSource.all).toHaveLength(1));

		FakeEventSource.latest.open();
		update({ status: 'READY_FOR_PICKUP' });

		expect(await screen.findByText("Ready! We'll call you shortly")).toBeInTheDocument();
		expect(screen.queryByText(/We'll keep trying/)).not.toBeInTheDocument();
	});
});
