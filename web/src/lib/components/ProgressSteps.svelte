<script lang="ts">
	import type { Status } from '#lib/api.ts';
	import { STEPS, currentStep } from '#lib/status.ts';

	let { status }: { status: Status } = $props();

	const current = $derived(currentStep(status));
</script>

<ol>
	{#each STEPS as step, index (step.label)}
		<li class:done={index < current} class:current={index === current} aria-current={index === current ? 'step' : undefined}>
			<div>{step.label}<span class="hint">{step.hint}</span></div>
		</li>
	{/each}
</ol>

<style>
	ol {
		list-style: none;
		margin: 0;
		padding: 0;
	}
	li {
		position: relative;
		display: flex;
		gap: 14px;
		padding-bottom: 18px;
		color: var(--muted);
	}
	li:last-child {
		padding-bottom: 0;
	}
	/* the dot */
	li::before {
		content: '';
		flex: none;
		width: 22px;
		height: 22px;
		margin-top: 1px;
		border-radius: 50%;
		border: 2px solid var(--border);
		background: var(--surface);
		z-index: 1;
	}
	/* the line to the next step */
	li:not(:last-child)::after {
		content: '';
		position: absolute;
		left: 10px;
		top: 24px;
		bottom: 0;
		width: 2px;
		background: var(--border);
	}
	.done {
		color: var(--text);
	}
	.done::before {
		border-color: var(--brand);
		background: var(--brand);
		box-shadow: inset 0 0 0 4px var(--surface);
	}
	.done::after {
		background: var(--brand);
	}
	.current {
		color: var(--text);
		font-weight: 700;
	}
	.current::before {
		border-color: var(--brand);
		border-width: 6px;
	}
	.hint {
		display: block;
		font-weight: 400;
		font-size: 0.875rem;
		color: var(--muted);
	}
</style>
