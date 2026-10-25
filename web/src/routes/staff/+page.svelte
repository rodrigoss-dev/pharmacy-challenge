<script lang="ts">
	import { onMount } from 'svelte';
	import { api, type Health, type Medication, type StaffPrescription, type WorkQueues } from '#lib/api.ts';
	import TopBar from '#lib/components/TopBar.svelte';
	import { latestOnly, poll } from '#lib/polling.ts';
	import { minutesSince, plural } from '#lib/util.ts';

	// Deliberately plain: polls every 2 seconds (the next poll waits for the last one), one button per step.

	interface Action {
		path: string;
		label: string;
		primary: boolean;
	}

	let queues = $state<WorkQueues | null>(null);
	let medications = $state<Medication[]>([]);
	let health = $state<Health | null>(null);
	/** The last button press failed; cleared by the next one that works. */
	let error = $state<string | null>(null);
	/** The last refresh failed; cleared by the next refresh that works. */
	let refreshError = $state<string | null>(null);
	let busy = $state(false);

	const problems = $derived.by(() => {
		const found: string[] = [];
		if (!health) return found;
		if (health.database === 'down') {
			found.push(
				'The database is unreachable: nothing can be saved right now, and patient screens say their updates are delayed. This console catches up by itself when it is back.'
			);
		}
		if (health.broker === 'down') {
			found.push(
				'Message broker (RabbitMQ) is unreachable. Check-ins and the buttons below still work; stock checks run from the database every few seconds instead, and live screens refresh every 10 seconds.'
			);
		}
		if (health.brokerBlocked) {
			found.push(`RabbitMQ is refusing new messages (${health.brokerBlocked}). Events wait until it has memory or disk space again.`);
		}
		if ((health.pendingEvents ?? 0) > 20) {
			const oldest = health.oldestPendingEventSeconds;
			found.push(`${health.pendingEvents} events waiting to be delivered${oldest ? `, the oldest for ${duration(oldest)}` : ''}.`);
		}
		if ((health.stalledStockChecks ?? 0) > 0) {
			found.push(
				`${plural(health.stalledStockChecks!, 'stock check')} taking longer than usual. They are retried automatically; you can also retry them by hand under Stock check.`
			);
		}
		if ((health.deadLetters ?? 0) > 0) {
			found.push(
				`${plural(health.deadLetters!, 'stock check message')} failed repeatedly and were parked in the dead-letter queue. Their prescriptions are retried automatically; the messages stay in RabbitMQ for inspection.`
			);
		}
		// A commit is normally sent within seconds of the hand-over: only a wait of a minute or more is worth a warning.
		if ((health.oldestPendingGovernmentCommitSeconds ?? 0) >= 60) {
			found.push(
				`${plural(health.pendingGovernmentCommits!, 'commit')} to the government system waiting, the oldest for ${duration(health.oldestPendingGovernmentCommitSeconds!)}. The government system may be down. They are sent again automatically and nothing is lost.`
			);
		}
		if ((health.rejectedGovernmentCommits ?? 0) > 0) {
			found.push(
				`${plural(health.rejectedGovernmentCommits!, 'commit')} to the government system refused for good. The patients have their medication already; someone has to sort it out with the government (the reason is in the backend log).`
			);
		}
		return found;
	});

	onMount(() => poll(refresh, 2000));

	/**
	 * Each part on its own: a failing health check must not blank the queues, nor the other way round. Pressing a button
	 * refreshes too, while a poll may be on its way: only the answer to the last one asked is shown.
	 */
	const refresh = latestOnly(
		() =>
			Promise.allSettled([
				api<WorkQueues>('/api/staff/queues'),
				api<Medication[]>('/api/medications'),
				api<Health>('/health')
			]),
		([nextQueues, nextMedications, nextHealth]) => {
			if (nextQueues.status === 'fulfilled') queues = nextQueues.value;
			if (nextMedications.status === 'fulfilled') medications = nextMedications.value;
			if (nextHealth.status === 'fulfilled') health = nextHealth.value;
			const failed = [nextQueues, nextMedications, nextHealth].find((result) => result.status === 'rejected');
			refreshError = failed ? (failed.reason as Error).message : null;
		}
	);

	function duration(seconds: number): string {
		return seconds < 120 ? `${seconds}s` : `${Math.floor(seconds / 60)} min`;
	}

	function actionsFor(queue: keyof WorkQueues, p: StaffPrescription): Action[] {
		switch (queue) {
			case 'stockCheck':
				return [{ path: 'retry-stock-check', label: 'Retry stock check', primary: true }];
			case 'pharmacist':
				return [
					{ path: 'approve', label: 'Approve', primary: true },
					{ path: 'reject', label: 'Reject...', primary: false }
				];
			case 'packaging':
				return [{ path: 'packed', label: 'Packed', primary: true }];
			case 'pickup':
				return p.status === 'CALLED'
					? [
							{ path: 'call', label: 'Call again', primary: false },
							{ path: 'no-show', label: 'No show', primary: false },
							{ path: 'hand-over', label: 'Handed over', primary: true }
						]
					: [{ path: 'call', label: `Call number ${p.ticketNumber}`, primary: true }];
		}
	}

	async function act(p: StaffPrescription, action: Action) {
		let body: { reason: string } | undefined;
		if (action.path === 'reject') {
			const reason = prompt('Why? (up to 200 characters) The patient will see this, and will be asked to come to the counter.');
			if (!reason) return;
			body = { reason };
		}
		if (
			action.path === 'no-show' &&
			!confirm(
				`Number ${p.ticketNumber} did not answer? Its medication goes back on the shelf, and the patient will have to check in again.`
			)
		) {
			return;
		}
		await send(`/api/staff/prescriptions/${p.id}/${action.path}`, body);
	}

	async function send(path: string, body?: unknown) {
		busy = true;
		try {
			await api(path, { method: 'POST', body });
			error = null;
		} catch (e) {
			error = (e as Error).message;
		} finally {
			busy = false;
		}
		await refresh();
	}
</script>

<svelte:head><title>Staff console - Greenleaf Pharmacy</title></svelte:head>

{#snippet queue(title: string, key: keyof WorkQueues, note: string)}
	{@const prescriptions = queues?.[key] ?? []}
	<section class="card">
		<h2>{title} <span class="count">({prescriptions.length})</span></h2>
		{#if note}<p class="muted small">{note}</p>{/if}
		<ul>
			{#each prescriptions as p (p.id)}
				<li>
					<div class="who">
						<span class="num">#{p.ticketNumber}</span>
						{p.patientName}
						{#if p.status === 'CALLED'}<span class="tag attention">called</span>{/if}
						<span class="tag">{minutesSince(p.updatedAt)} min in this step</span>
						{#if p.governmentNumber}<span class="tag">government prescription {p.governmentNumber}</span>{/if}
						<div class="muted small">{p.items.map((i) => `${i.name} × ${i.quantity}`).join(', ')}</div>
					</div>
					{#each actionsFor(key, p) as action (action.path)}
						<button class="btn" class:btn-primary={action.primary} disabled={busy} onclick={() => act(p, action)}>
							{action.label}
						</button>
					{/each}
				</li>
			{:else}
				<li class="muted">{queues ? 'Empty' : 'Not loaded yet'}</li>
			{/each}
		</ul>
	</section>
{/snippet}

<TopBar title="Staff console">
	refreshes every 2s &middot; <a href="/board" target="_blank">board</a> &middot; <a href="/" target="_blank">check-in</a>
</TopBar>

<main class="page staff">
	{#if problems.length > 0}
		<div class="alert warn" role="status">{problems.join(' ')}</div>
	{/if}
	<!-- A button press and the refresh often fail the same way (the backend is away): then it is one banner, not two. -->
	{#if error}
		<div class="alert" role="alert">{error}{refreshError === error ? ' The console keeps trying.' : ''}</div>
	{/if}
	{#if refreshError && refreshError !== error}
		<div class="alert" role="alert">{refreshError} The console keeps trying.</div>
	{/if}

	{@render queue(
		'Stock check',
		'stockCheck',
		'Automatic. If one gets stuck (e.g. message broker down), the system retries it within about 25 seconds; you can also retry it by hand.'
	)}
	{@render queue('Pharmacist: approval queue', 'pharmacist', '')}
	{@render queue('Packaging queue', 'packaging', '')}
	{@render queue(
		'Pharmacist: pickup counter',
		'pickup',
		'Called and nobody came? Call again, and if there is still no answer mark No show: the medication goes back on the shelf.'
	)}

	<section class="card">
		<h2>Inventory</h2>
		<table>
			<thead><tr><th>Medication</th><th>Category</th><th>Available packs</th><th></th></tr></thead>
			<tbody>
				{#each medications as m (m.id)}
					<tr>
						<td>{m.name}</td>
						<td>{m.category}</td>
						<td class="number">
							{m.stock}
							{#if m.stock === 0}<span class="tag attention">out of stock</span>{/if}
						</td>
						<td>
							<button
								class="btn"
								disabled={busy}
								onclick={() => send(`/api/staff/medications/${m.id}/restock`, { quantity: 10 })}>+10 packs</button
							>
						</td>
					</tr>
				{/each}
			</tbody>
		</table>
	</section>
</main>

<style>
	.staff {
		max-width: 1100px;
	}
	h2 {
		font-size: 1.15rem;
	}
	.count {
		font-size: 0.9rem;
		color: var(--muted);
		font-weight: 500;
	}
	ul {
		list-style: none;
		margin: 0;
		padding: 0;
	}
	li {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: 12px;
		padding: 12px 0;
		border-top: 1px solid var(--border);
	}
	.who {
		flex: 1;
		min-width: 220px;
	}
	.num {
		font-weight: 800;
		font-size: 1.3rem;
		font-variant-numeric: tabular-nums;
		margin-right: 0.35rem;
	}
	li .btn,
	td .btn {
		min-height: 40px;
	}
	table {
		width: 100%;
		border-collapse: collapse;
	}
	th,
	td {
		text-align: left;
		padding: 8px 6px;
		border-top: 1px solid var(--border);
	}
	.number {
		font-variant-numeric: tabular-nums;
	}
</style>
