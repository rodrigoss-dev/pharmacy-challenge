<script lang="ts">
	let { value = $bindable(0), max, label }: { value?: number; max: number; label: string } = $props();
</script>

<div class="stepper" role="group" aria-label="Packs of {label}">
	<button
		type="button"
		disabled={value <= 0}
		aria-label="One pack less of {label}"
		onclick={() => (value = Math.max(0, value - 1))}>&minus;</button
	>
	<output aria-live="polite">{value}</output>
	<button
		type="button"
		disabled={value >= max}
		aria-label="One pack more of {label}"
		onclick={() => (value = Math.min(max, value + 1))}>+</button
	>
</div>

<style>
	.stepper {
		display: flex;
		align-items: center;
		gap: 4px;
	}
	button {
		width: 44px;
		height: 44px;
		border-radius: 50%;
		border: 1px solid var(--border);
		background: var(--surface);
		color: var(--text);
		font-size: 1.4rem;
		line-height: 1;
		cursor: pointer;
	}
	button:disabled {
		opacity: 0.35;
		cursor: not-allowed;
	}
	output {
		min-width: 2ch;
		text-align: center;
		font-weight: 700;
		font-size: 1.1rem;
		font-variant-numeric: tabular-nums;
	}
</style>
