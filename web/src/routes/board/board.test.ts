// @vitest-environment jsdom
import { cleanup, render, screen, within } from '@testing-library/svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { FakeEventSource } from '../../test/fake-event-source.ts';
import Page from './+page.svelte';

beforeEach(() => {
	FakeEventSource.reset();
	vi.stubGlobal('EventSource', FakeEventSource);
});

afterEach(() => {
	cleanup();
	vi.unstubAllGlobals();
});

const section = (heading: RegExp) => screen.getByRole('heading', { name: heading }).closest('section')!;

describe('the waiting-room board', () => {
	it('starts empty, and connects to the live board', () => {
		render(Page);

		expect(FakeEventSource.latest.url).toBe('http://localhost:8080/api/board/live');
		expect(screen.getByText('No one right now')).toBeInTheDocument();
		expect(screen.getByText('Nothing waiting')).toBeInTheDocument();
		expect(screen.getByText('All caught up')).toBeInTheDocument();
		expect(screen.getByText('Connecting...')).toBeInTheDocument();
	});

	it('shows ticket numbers by stage as the backend sends them', async () => {
		render(Page);
		FakeEventSource.latest.open();

		FakeEventSource.latest.send('board', { nowCalling: [3], ready: [5, 6], inProgress: [7, 8, 9] });

		const numbers = async (heading: RegExp) => (await within(section(heading)).findAllByRole('listitem')).map((item) => item.textContent);
		expect(await numbers(/Now calling/)).toEqual(['3']);
		expect(await numbers(/Ready, we'll call you soon/)).toEqual(['5', '6']);
		expect(await numbers(/Being prepared/)).toEqual(['7', '8', '9']);
		expect(screen.getByText('Live')).toBeInTheDocument();
	});

	it('shows only numbers: a public screen', async () => {
		render(Page);
		FakeEventSource.latest.send('board', { nowCalling: [3], ready: [], inProgress: [] });

		await screen.findByText('3');
		expect(document.body.textContent).not.toMatch(/Maria|patientName/);
	});

	it('empties a stage when its last number leaves', async () => {
		render(Page);
		FakeEventSource.latest.send('board', { nowCalling: [3], ready: [], inProgress: [] });
		await screen.findByText('3');

		FakeEventSource.latest.send('board', { nowCalling: [], ready: [], inProgress: [] });

		expect(await screen.findByText('No one right now')).toBeInTheDocument();
		expect(screen.queryByText('3')).not.toBeInTheDocument();
	});

	it('says when its updates are delayed, and hangs up when the screen goes away', async () => {
		const { unmount } = render(Page);
		FakeEventSource.latest.open();

		FakeEventSource.latest.send('unavailable', { error: 'later' });
		expect(await screen.findByText('Updates delayed: catching up by itself')).toBeInTheDocument();

		unmount();
		expect(FakeEventSource.latest.closed).toBe(true);
	});
});
