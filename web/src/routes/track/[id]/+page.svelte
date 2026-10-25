<script lang="ts">
	import { page } from '$app/state';
	import { api, ApiError, FINAL_STATUSES, type Tracking } from '#lib/api.ts';
	import { CallAlert } from '#lib/call-alert.ts';
	import LiveIndicator from '#lib/components/LiveIndicator.svelte';
	import ProgressSteps from '#lib/components/ProgressSteps.svelte';
	import TopBar from '#lib/components/TopBar.svelte';
	import { liveStream, type ConnectionState } from '#lib/live.ts';
	import { describe } from '#lib/status.ts';
	import { activeTicket, time } from '#lib/util.ts';

	let tracking = $state<Tracking | null>(null);
	let notFound = $state(false);
	let loadError = $state<string | null>(null);
	let connection = $state<ConnectionState>('connecting');
	let soundOn = $state(false);
	const callAlert = new CallAlert();

	const isFinal = $derived(tracking !== null && FINAL_STATUSES.includes(tracking.status));
	const title = $derived.by(() => {
		if (!tracking) return 'Your prescription - Greenleaf Pharmacy';
		if (tracking.status === 'CALLED') return `Your turn! Number ${tracking.ticketNumber}`;
		return `${describe(tracking).headline} - number ${tracking.ticketNumber}`;
	});

	// Load the prescription, then follow it live until it is finished or the patient leaves the page.
	$effect(() => {
		const id = page.params.id;
		if (!id) return;
		let stream: { close(): void } | undefined;
		let leftPage = false;
		let finished = false;
		let previous: Tracking | null = null;

		function show(next: Tracking) {
			// Alert on every new call (the pharmacist can call again), but not just because the page was opened.
			if (next.status === 'CALLED' && previous && next.updatedAt !== previous.updatedAt) callAlert.alert();
			previous = next;
			tracking = next;
			loadError = null;
			if (FINAL_STATUSES.includes(next.status)) {
				finished = true;
				activeTicket.clear(next.id);
				stream?.close();
			}
		}

		(async () => {
			try {
				show(await api<Tracking>(`/api/prescriptions/${id}`, { retries: 2 }));
			} catch (e) {
				if (e instanceof ApiError && (e.status === 404 || e.status === 400)) {
					activeTicket.clear(id);
					notFound = true;
					return;
				}
				// Network trouble: say so, and let the live stream below deliver the status when it can.
				loadError = `${(e as Error).message} We'll keep trying.`;
			}
			if (leftPage || finished) return;
			stream = liveStream(
				`/api/prescriptions/${id}/live`,
				{
					status: (data) => show(data as Tracking),
					'not-found': () => {
						notFound = true;
						stream?.close();
					}
				},
				(state) => (connection = state)
			);
		})();

		return () => {
			leftPage = true;
			stream?.close();
		};
	});

	function toggleSound() {
		soundOn = !soundOn;
		if (soundOn) callAlert.enableSound();
		else callAlert.disableSound();
	}
</script>

<svelte:head><title>{title}</title></svelte:head>

<TopBar />

<main class="page">
	{#if notFound}
		<div class="card">
			<h1>We can't find this ticket</h1>
			<p>The link may be incomplete. Please check in again, or ask us at the counter.</p>
			<a class="btn btn-primary" href="/">Check in</a>
		</div>
	{:else if !tracking}
		<p class="muted">{loadError ?? 'Loading your prescription...'}</p>
	{:else}
		{@const message = describe(tracking)}
		<section class="card ticket" aria-labelledby="ticket-label">
			<p id="ticket-label" class="label">Your number</p>
			<p class="number">{tracking.ticketNumber}</p>
			<p class="muted">Hi {tracking.patientName.split(' ')[0]}</p>
		</section>

		<!-- role=status: screen readers announce each change without the patient having to look for it -->
		<section class="card status" data-tone={message.tone} role="status" aria-live="polite">
			<h1>{message.headline}</h1>
			<p>{message.detail}</p>
		</section>

		<!-- However it ended (collected, out of stock, rejected, nobody came), the patient can start over from here. -->
		{#if isFinal}
			<div class="actions again">
				<a class="btn btn-primary" href="/">Start a new check-in</a>
			</div>
		{/if}

		{#if message.tone !== 'problem'}
			<section class="card" aria-label="Progress">
				<ProgressSteps status={tracking.status} />
			</section>
		{/if}

		<section class="card">
			<h2 class="small muted">Your prescription</h2>
			<ul class="items">
				{#each tracking.items as item (item.medicationId)}
					<li><span>{item.name}</span><span>{item.quantity} pack{item.quantity > 1 ? 's' : ''}</span></li>
				{/each}
			</ul>
			<p class="muted small">Checked in at {time(tracking.checkedInAt)}</p>
		</section>

		{#if !isFinal}
			<div class="actions">
				<button class="btn" type="button" aria-pressed={soundOn} onclick={toggleSound}>
					{soundOn ? "Sound on: we'll beep when it's your turn" : "Play a sound when it's my turn"}
				</button>
			</div>
			<LiveIndicator
				{connection}
				live="Live: this page updates by itself"
				reconnecting="Reconnecting... your place in line is safe"
				delayed="Updates are delayed... your place in line is safe"
			/>
		{/if}
	{/if}
</main>

<style>
	.ticket {
		text-align: center;
		padding: 24px 20px 20px;
	}
	.label {
		margin: 0;
		text-transform: uppercase;
		letter-spacing: 0.08em;
		font-size: 0.8rem;
		font-weight: 700;
		color: var(--muted);
	}
	.number {
		margin: 0.25rem 0;
		font-size: clamp(4.5rem, 24vw, 7rem);
		font-weight: 800;
		line-height: 1;
		font-variant-numeric: tabular-nums;
		color: var(--brand-strong);
	}

	.status {
		border-width: 2px;
	}
	.status h1 {
		font-size: 1.45rem;
	}
	.status p {
		margin-bottom: 0;
		/* A pharmacist's reason is free text: a long word must wrap, not push the page sideways. */
		overflow-wrap: anywhere;
	}
	.status[data-tone='ready'] {
		border-color: var(--brand);
		background: var(--success-bg);
	}
	.status[data-tone='delayed'] {
		border-color: var(--warn-text);
		background: var(--warn-bg);
	}
	.status[data-tone='problem'] {
		border-color: var(--danger-text);
		background: var(--danger-bg);
	}
	.status[data-tone='problem'] h1 {
		color: var(--danger-text);
	}
	/* "It's your turn": the one moment that must not be missed. */
	.status[data-tone='called'] {
		border-color: var(--brand);
		background: var(--brand);
		color: var(--on-brand);
		text-align: center;
		padding: 28px 20px;
	}
	.status[data-tone='called'] h1 {
		font-size: 2rem;
	}
	@media (prefers-reduced-motion: no-preference) {
		.status[data-tone='called'] {
			animation: pulse 1.6s ease-in-out infinite;
		}
	}
	@keyframes pulse {
		50% {
			transform: scale(1.02);
			box-shadow: 0 0 0 10px rgb(10 122 92 / 0.2);
		}
	}

	.items {
		list-style: none;
		padding: 0;
		margin: 0.5rem 0 0.75rem;
	}
	.items li {
		display: flex;
		justify-content: space-between;
		padding: 6px 0;
		border-top: 1px solid var(--border);
	}

	.actions {
		display: flex;
		justify-content: center;
		margin-top: 12px;
	}
	/* Under the status card it belongs to, and above what follows: the card has no bottom margin of its own to spare. */
	.again {
		margin: -4px 0 16px;
	}
</style>
