<script lang="ts">
	import { goto } from '$app/navigation';
	import { onMount } from 'svelte';
	import {
		api,
		ApiError,
		FINAL_STATUSES,
		type CheckInResponse,
		type GovernmentPrescription,
		type Medication,
		type Tracking
	} from '#lib/api.ts';
	import { checkInIds } from '#lib/checkin.ts';
	import QuantityStepper from '#lib/components/QuantityStepper.svelte';
	import TopBar from '#lib/components/TopBar.svelte';
	import { cap, governmentNote, offered, SAMPLE_PRESCRIPTIONS } from '#lib/government.ts';
	import { latestOnly } from '#lib/polling.ts';
	import { activeTicket, plural, type ActiveTicket } from '#lib/util.ts';

	// One id per check-in: if the response gets lost and the patient taps again, they keep the same ticket. If they
	// change what they ask for after a failed attempt, it is another check-in and gets an id of its own.
	const idFor = checkInIds();
	/** The ticket remembered from an earlier visit. Shown at once, and dropped if it turns out to be over (see onMount). */
	let resume = $state<ActiveTicket | null>(activeTicket.get());

	let medications = $state<Medication[] | null>(null);
	/** Why the medication could not be loaded, while it cannot: the page keeps trying (see loadMedications). */
	let loadError = $state<string | null>(null);
	let loadTimer: ReturnType<typeof setTimeout> | undefined;
	let leftPage = false;
	let quantities = $state<Record<string, number>>({});
	let name = $state('');
	let nameInput: HTMLInputElement;
	let nameInvalid = $state(false);
	let attempted = $state(false);
	let submitting = $state(false);
	let serverError = $state<string | null>(null);

	let governmentNumber = $state('');
	/** Set once a government prescription number was looked up: the medication to choose from is then its own. */
	let government = $state<GovernmentPrescription | null>(null);
	let lookingUp = $state(false);
	let lookupError = $state<string | null>(null);

	/** The government prescription's medication that the pharmacy carries, with the packs that can be taken of each... */
	const governmentItems = $derived(offered(government, medications).stocked);
	/** ...and the medication it lists that the pharmacy does not carry at all. */
	const unstocked = $derived(offered(government, medications).unstocked);

	const selected = $derived(Object.entries(quantities).filter(([, quantity]) => quantity > 0));
	const packs = $derived(selected.reduce((sum, [, quantity]) => sum + quantity, 0));
	const error = $derived(
		attempted && selected.length === 0 ? 'Please choose at least one medication from your prescription.' : serverError
	);

	// A name made of spaces satisfies `required`, but it is no name: refuse it like an empty one, with the same message
	// and the same inline error, rather than letting the server answer at the bottom of the form.
	$effect(() => {
		nameInput?.setCustomValidity(name.trim() === '' ? 'Please tell us your name.' : '');
	});

	onMount(() => {
		void loadMedications();
		void checkResume();
		// Phones suspend background tabs: look at the shelf again as soon as the patient comes back to the screen.
		const onVisible = () => document.visibilityState === 'visible' && void loadMedications();
		document.addEventListener('visibilitychange', onVisible);
		return () => {
			leftPage = true;
			clearTimeout(loadTimer);
			document.removeEventListener('visibilitychange', onVisible);
		};
	});

	/** Only the answer to the call started last is shown, so a slow older answer never puts back stock that is gone. */
	const readCatalog = latestOnly(
		// The first load is worth retrying; a later one is replaced by the next, a few seconds away.
		() => api<Medication[]>('/api/medications', { retries: medications === null ? 2 : 0 }),
		showCatalog
	);

	/**
	 * Loads the medication to choose from, and keeps loading it while the patient is on the page: packs go back on the
	 * shelf (a number that did not answer, a rejection, a restock) and are taken by other check-ins while they choose, so
	 * what is on offer must follow. If the backend cannot answer (it is restarting, or its database is down), the page
	 * says so while it has nothing to show, and tries again by itself, so the patient never has to reload it: the
	 * check-in is unusable without it. Once there is a catalog, a failed read just keeps the last one on screen.
	 */
	async function loadMedications() {
		clearTimeout(loadTimer);
		try {
			await readCatalog();
			loadError = null;
		} catch (e) {
			loadError = (e as Error).message;
		}
		// Cleared again: a read started by hand or by the tab coming back may have scheduled the next one meanwhile.
		clearTimeout(loadTimer);
		if (!leftPage) loadTimer = setTimeout(loadMedications, 5000 + Math.random() * 2000);
	}

	/** Keeps what the patient chose, but never more packs than the shelf holds now. */
	function showCatalog(catalog: Medication[]) {
		medications = catalog;
		quantities = Object.fromEntries(catalog.map((m) => [m.id, Math.min(quantities[m.id] ?? 0, limit(m))]));
	}

	/** The most packs of [m] that can be chosen right now (the same as the steppers in the list allow). */
	function limit(m: Medication): number {
		if (!government) return m.stock;
		const item = government.items.find((i) => i.medicationId === m.id);
		return item ? cap(item, m) : 0;
	}

	/** A ticket remembered from before may be over by now (collected, given up on...): then there is nothing to continue. */
	async function checkResume() {
		const ticket = resume;
		if (!ticket) return;
		try {
			const tracking = await api<Tracking>(`/api/prescriptions/${ticket.id}`);
			if (FINAL_STATUSES.includes(tracking.status)) forget(ticket);
		} catch (e) {
			if (e instanceof ApiError && (e.status === 404 || e.status === 400)) forget(ticket);
			// Anything else (offline, the backend restarting): keep the banner, its link still works.
		}
	}

	function forget(ticket: ActiveTicket) {
		activeTicket.clear(ticket.id);
		if (resume?.id === ticket.id) resume = null;
	}

	async function lookUp(event: SubmitEvent) {
		event.preventDefault();
		lookingUp = true;
		lookupError = null;
		try {
			// A read, so safe to retry once if the network hiccups. Not if the government system fails (the backend answers
			// 502, which is not retried): that takes its whole timeout, and the patient can try again or type their medication.
			const found = await api<GovernmentPrescription>(
				`/api/government-prescriptions/${encodeURIComponent(governmentNumber.trim())}`,
				{ retries: 1 }
			);
			government = found;
			if (governmentItems.length === 0) {
				government = null;
				lookupError = 'We do not stock any of the medication on this prescription. Please ask a pharmacist for help.';
				return;
			}
			// Everything on offer is chosen to begin with, up to what can be taken; the patient takes away what they do not want.
			quantities = Object.fromEntries(
				(medications ?? []).map((m) => [m.id, governmentItems.find((i) => i.medication.id === m.id)?.cap ?? 0])
			);
			serverError = null;
		} catch (e) {
			lookupError = (e as Error).message;
		} finally {
			lookingUp = false;
		}
	}

	/** Fills the number field with a sample, for the patient to look up: nothing is asked of the government system yet. */
	function useSample(number: string) {
		governmentNumber = number;
		lookupError = null;
	}

	function enterByHand() {
		government = null;
		lookupError = null;
		serverError = null;
		quantities = Object.fromEntries(Object.keys(quantities).map((id) => [id, 0]));
	}

	async function submit(event: SubmitEvent) {
		event.preventDefault();
		attempted = true;
		if (selected.length === 0) return;

		submitting = true;
		serverError = null;
		try {
			const items = selected.map(([medicationId, quantity]) => ({ medicationId, quantity }));
			// The pharmacy checks the government prescription again: what was shown here may have changed.
			const governmentNumber = government?.number;
			// Safe to retry: the same id gets the same ticket, never a second one.
			const ticket = await api<CheckInResponse>('/api/prescriptions', {
				method: 'POST',
				body: { id: idFor([name.trim(), items, governmentNumber]), patientName: name, items, governmentNumber },
				retries: 3
			});
			activeTicket.set(ticket);
			await goto(`/track/${ticket.id}`);
		} catch (e) {
			serverError = (e as Error).message;
			submitting = false;
		}
	}

	function availability(m: Medication) {
		if (m.stock === 0) return 'Out of stock: ask the pharmacist for an alternative';
		return m.stock <= 5 ? `Only ${m.stock} left` : 'In stock';
	}
</script>

<svelte:head><title>Check in - Greenleaf Pharmacy</title></svelte:head>

<TopBar />

<main class="page">
	{#if resume}
		<div class="alert info">
			You already have number <strong>{resume.ticketNumber}</strong> in progress.
			<a href="/track/{resume.id}">See its status</a>
		</div>
	{/if}

	<h1>Check in your prescription</h1>
	<p class="lead">
		Tell us what's on your prescription. You'll get a number, and this page will keep you posted until we call it.
		No need to queue at the counter.
	</p>

	{#if !government}
		<form class="card" onsubmit={lookUp}>
			<label for="government-number">Government prescription number <span class="muted">(optional)</span></label>
			<p class="muted small">Enter it and we'll fill in your medication for you.</p>
			<div class="lookup">
				<input
					id="government-number"
					type="text"
					autocomplete="off"
					autocapitalize="characters"
					maxlength="40"
					aria-describedby={lookupError ? 'lookup-error' : undefined}
					bind:value={governmentNumber}
				/>
				<button class="btn" type="submit" disabled={lookingUp || medications === null || governmentNumber.trim() === ''}>
					{lookingUp ? 'Looking up...' : 'Look up'}
				</button>
			</div>
			{#if lookupError}
				<div id="lookup-error" class="alert lookup-error" role="alert">{lookupError}</div>
			{/if}
			<div class="samples" role="group" aria-labelledby="samples-title">
				<p id="samples-title" class="muted small">No number at hand? Tap a sample to fill it in, then look it up.</p>
				<ul>
					{#each SAMPLE_PRESCRIPTIONS as sample (sample.number)}
						<li>
							<button type="button" class="sample" onclick={() => useSample(sample.number)}>
								<span class="sample-number">{sample.number}</span>
								<span class="sample-explanation">{sample.explanation}</span>
							</button>
						</li>
					{/each}
				</ul>
			</div>
		</form>
	{/if}

	<form class="card" onsubmit={submit}>
		<div class="field">
			<label for="name">Your name</label>
			<!-- aria-invalid follows the :user-invalid styling, so screen readers hear the error when it shows.
			     The browser fires "invalid" (and no submit event) when the form is sent without a name. -->
			<input
				id="name"
				type="text"
				autocomplete="given-name"
				required
				maxlength="60"
				aria-errormessage="name-error"
				aria-invalid={nameInvalid ? 'true' : undefined}
				bind:value={name}
				bind:this={nameInput}
				onblur={() => (nameInvalid = nameInput.matches(':user-invalid'))}
				oninput={() => nameInvalid && (nameInvalid = !nameInput.validity.valid)}
				oninvalid={() => (nameInvalid = true)}
			/>
			<p id="name-error" class="field-error"><span aria-hidden="true">!</span> Please tell us your name.</p>
		</div>

		<fieldset class="field">
			{#if government}
				<legend>Medication on government prescription {government.number}</legend>
				<p class="muted small">
					Choose what you're collecting today. Some medication has a maximum number of packs, the rest comes as prescribed.
					<button type="button" class="link" onclick={enterByHand}>Enter my medication by hand instead</button>
				</p>
			{:else}
				<legend>Medication on your prescription</legend>
				<p class="muted small">Choose how many packs your doctor prescribed.</p>
			{/if}
			{#if medications === null}
				{#if loadError}
					<div class="alert" role="alert">
						{loadError} We'll keep trying.
						<button type="button" class="link" onclick={loadMedications}>Try now</button>
					</div>
				{:else}
					<p class="muted" aria-busy="true">Loading medication...</p>
				{/if}
			{:else if government}
				<ul class="medications">
					{#each governmentItems as item (item.medication.id)}
						{@const m = item.medication}
						<li class:selected={quantities[m.id] > 0} class:unavailable={item.cap === 0}>
							<div class="info">
								<div class="name">{m.name}</div>
								<div class="meta">{m.category} &middot; {governmentNote(item, m, item.cap)}</div>
							</div>
							{#if item.maximum}
								<QuantityStepper bind:value={quantities[m.id]} max={item.cap} label={m.name} />
							{:else}
								<label class="include">
									<input
										type="checkbox"
										aria-label="Include {m.name}"
										checked={quantities[m.id] > 0}
										disabled={item.cap === 0}
										onchange={(e) => (quantities[m.id] = e.currentTarget.checked ? item.cap : 0)}
									/>
									Include
								</label>
							{/if}
						</li>
					{/each}
				</ul>
				{#if unstocked.length > 0}
					<p class="muted small unstocked">
						Not stocked here, please ask a pharmacist: {unstocked.map((item) => item.medicationId).join(', ')}.
					</p>
				{/if}
			{:else}
				<ul class="medications">
					{#each medications as m (m.id)}
						{@const max = m.stock}
						<li class:selected={quantities[m.id] > 0} class:unavailable={max === 0}>
							<div class="info">
								<div class="name">{m.name}</div>
								<div class="meta">
									{m.category} &middot; <span class:low={m.stock > 0 && m.stock <= 5}>{availability(m)}</span>
								</div>
							</div>
							<QuantityStepper bind:value={quantities[m.id]} {max} label={m.name} />
						</li>
					{/each}
				</ul>
			{/if}
		</fieldset>

		{#if error}
			<div class="alert" role="alert">{error}</div>
		{/if}

		<p class="summary">
			{selected.length === 0 ? 'Nothing selected yet' : `${plural(selected.length, 'medication')}, ${plural(packs, 'pack')}`}
		</p>
		<button class="btn btn-primary submit" type="submit" disabled={submitting}>
			{submitting ? 'Checking you in...' : 'Get my number'}
		</button>
	</form>

	<p class="muted small">Prefer to talk to someone? Our pharmacists are happy to help at the counter.</p>
</main>

<style>
	.field {
		margin-bottom: 20px;
	}
	fieldset {
		border: 0;
		padding: 0;
		min-width: 0;
	}
	label,
	legend {
		display: block;
		font-weight: 600;
		margin-bottom: 0.35rem;
		padding: 0;
	}
	input {
		width: 100%;
		min-height: 52px;
		padding: 0 14px;
		border: 1px solid var(--border);
		border-radius: 12px;
		background: var(--surface);
		color: var(--text);
		font: inherit;
		font-size: 1.1rem;
	}
	/* Only after the patient has interacted with the field (or tried to send the form). */
	input:user-invalid {
		border-color: var(--danger-text);
	}
	.field-error {
		display: none;
		color: var(--danger-text);
		font-size: 0.9rem;
		margin: 0.35rem 0 0;
	}
	input:user-invalid ~ .field-error {
		display: block;
	}

	.medications {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
		gap: 10px;
	}
	.medications li {
		display: flex;
		align-items: center;
		gap: 12px;
		padding: 12px 12px 12px 16px;
		border: 1px solid var(--border);
		border-radius: 14px;
		background: var(--surface);
	}
	.medications li.selected {
		border-color: var(--brand);
		background: var(--success-bg);
	}
	.medications li.unavailable {
		opacity: 0.6;
	}
	.info {
		flex: 1;
		min-width: 0;
	}
	.name {
		font-weight: 600;
	}
	.meta {
		color: var(--muted);
		font-size: 0.875rem;
	}
	.low {
		color: var(--warn-text);
	}

	.lookup {
		display: flex;
		gap: 10px;
	}
	.lookup input {
		flex: 1;
		min-width: 0;
	}
	.lookup-error {
		margin: 12px 0 0;
	}
	.samples {
		margin-top: 16px;
	}
	.samples p {
		margin-bottom: 8px;
	}
	.samples ul {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
		gap: 8px;
	}
	/* The whole row is the button: a big target on a phone, and the explanation is part of what it announces. */
	.sample {
		display: flex;
		flex-direction: column;
		align-items: flex-start;
		gap: 2px;
		width: 100%;
		min-height: 48px;
		padding: 10px 14px;
		border: 1px solid var(--border);
		border-radius: 12px;
		background: var(--surface-2);
		color: var(--text);
		font: inherit;
		text-align: left;
		cursor: pointer;
	}
	.sample:hover {
		border-color: var(--brand);
	}
	.sample-number {
		font-weight: 700;
		font-variant-numeric: tabular-nums;
		color: var(--brand-strong);
	}
	.sample-explanation {
		color: var(--muted);
		font-size: 0.875rem;
	}
	/* The check-in form's input style is for text; a checkbox keeps its natural size. */
	.include {
		display: flex;
		align-items: center;
		gap: 8px;
		margin: 0;
		font-weight: 600;
		cursor: pointer;
	}
	.include input {
		width: 24px;
		min-height: 24px;
		height: 24px;
		padding: 0;
		accent-color: var(--brand);
	}
	.include input:disabled {
		cursor: not-allowed;
	}
	.link {
		padding: 0;
		border: 0;
		background: none;
		color: var(--brand-strong);
		font: inherit;
		text-decoration: underline;
		cursor: pointer;
	}

	.summary {
		color: var(--muted);
		margin: 8px 2px 12px;
	}
	.submit {
		width: 100%;
		min-height: 56px;
		font-size: 1.1rem;
	}
</style>
