<script lang="ts">
	import type { Snippet } from 'svelte';

	let { title = 'Greenleaf Pharmacy', children }: { title?: string; children?: Snippet } = $props();
</script>

<header>
	<a href="/" class="brand"><span class="logo" aria-hidden="true">+</span>{title}</a>
	{#if children}
		<div class="end">{@render children()}</div>
	{/if}
</header>

<style>
	header {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		padding: 0.85rem 16px;
		background: var(--surface);
		border-bottom: 1px solid var(--border);
	}
	.brand {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		font-weight: 700;
		color: inherit;
		text-decoration: none;
	}
	.logo {
		display: grid;
		place-items: center;
		width: 2rem;
		height: 2rem;
		border-radius: 8px;
		background: var(--brand);
		color: var(--on-brand);
		font-size: 1.25rem;
	}
	.end {
		margin-left: auto;
		color: var(--muted);
		font-size: 0.875rem;
	}
</style>
