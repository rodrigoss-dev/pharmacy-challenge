<script lang="ts">
	import { onMount } from 'svelte';
	import type { Board } from '#lib/api.ts';
	import LiveIndicator from '#lib/components/LiveIndicator.svelte';
	import TopBar from '#lib/components/TopBar.svelte';
	import { liveStream, type ConnectionState } from '#lib/live.ts';

	let board = $state<Board>({ nowCalling: [], ready: [], inProgress: [] });
	let connection = $state<ConnectionState>('connecting');
	let now = $state(new Date());

	onMount(() => {
		const stream = liveStream('/api/board/live', { board: (data) => (board = data as Board) }, (state) => (connection = state));
		const clock = setInterval(() => (now = new Date()), 10_000);
		return () => {
			stream.close();
			clearInterval(clock);
		};
	});
</script>

<svelte:head><title>Waiting room - Greenleaf Pharmacy</title></svelte:head>

{#snippet tickets(numbers: number[], empty: string)}
	{#if numbers.length > 0}
		<ul>
			{#each numbers as ticket (ticket)}
				<li>{ticket}</li>
			{/each}
		</ul>
	{:else}
		<p class="empty">{empty}</p>
	{/if}
{/snippet}

<div class="screen">
	<TopBar>{now.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}</TopBar>

	<!-- Ticket numbers only: this screen hangs in a public waiting room. -->
	<main aria-live="polite">
		<section class="calling">
			<h2>Now calling: please come to the counter</h2>
			{@render tickets(board.nowCalling, 'No one right now')}
		</section>
		<section>
			<h2>Ready, we'll call you soon</h2>
			{@render tickets(board.ready, 'Nothing waiting')}
		</section>
		<section>
			<h2>Being prepared</h2>
			{@render tickets(board.inProgress, 'All caught up')}
		</section>
	</main>

	<footer>
		<span>Follow your prescription live on your phone: open the link you got at check-in.</span>
		<LiveIndicator {connection} />
	</footer>
</div>

<style>
	.screen {
		min-height: 100dvh;
		display: flex;
		flex-direction: column;
	}
	main {
		flex: 1;
		display: grid;
		grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
		gap: 16px;
		padding: 16px;
	}
	section {
		background: var(--surface);
		border: 1px solid var(--border);
		border-radius: var(--radius);
		padding: 20px;
	}
	h2 {
		font-size: 1.1rem;
		text-transform: uppercase;
		letter-spacing: 0.06em;
		color: var(--muted);
	}
	ul {
		display: flex;
		flex-wrap: wrap;
		gap: 12px;
		list-style: none;
		padding: 0;
		margin: 0;
	}
	li {
		min-width: 3.2ch;
		padding: 6px 12px;
		border-radius: 12px;
		background: var(--surface-2);
		font-size: clamp(2rem, 5vw, 3.2rem);
		font-weight: 800;
		text-align: center;
		font-variant-numeric: tabular-nums;
	}
	.empty {
		color: var(--muted);
	}
	.calling {
		background: var(--brand);
		border-color: var(--brand);
		color: var(--on-brand);
	}
	.calling h2,
	.calling .empty {
		color: inherit;
	}
	.calling li {
		background: rgb(255 255 255 / 0.18);
		font-size: clamp(3rem, 9vw, 6rem);
	}
	footer {
		display: flex;
		flex-wrap: wrap;
		justify-content: space-between;
		align-items: center;
		gap: 8px;
		padding: 0 16px 16px;
		color: var(--muted);
	}
</style>
