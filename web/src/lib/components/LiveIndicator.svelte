<script lang="ts">
	import type { ConnectionState } from '#lib/live.ts';

	let {
		connection,
		live = 'Live',
		reconnecting = 'Reconnecting...',
		delayed = 'Updates delayed: catching up by itself'
	}: { connection: ConnectionState; live?: string; reconnecting?: string; delayed?: string } = $props();

	const text = $derived(
		{ live, reconnecting, delayed, connecting: 'Connecting...', closed: '' }[connection]
	);
</script>

{#if connection !== 'closed'}
	<p data-state={connection}>{text}</p>
{/if}

<style>
	p {
		display: flex;
		align-items: center;
		justify-content: center;
		gap: 0.5rem;
		margin: 8px 0 0;
		color: var(--muted);
		font-size: 0.875rem;
	}
	p::before {
		content: '';
		width: 9px;
		height: 9px;
		border-radius: 50%;
		background: var(--muted);
	}
	[data-state='live']::before {
		background: #1fbf7a;
	}
	[data-state='reconnecting']::before,
	[data-state='delayed']::before {
		background: #e0a400;
	}
</style>
