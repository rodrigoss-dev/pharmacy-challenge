import { defineConfig } from '@playwright/test';

/**
 * End-to-end tests: real browsers against the whole system, which must be running (`make dev`, or the backend, the web
 * app and the government mock started one by one). They check in as "E2E ..." patients and leave those tickets behind,
 * so point them at a development database, not at anything that matters.
 *
 *   WEB_URL   the web app (http://localhost:5173)
 *   GOV_URL   the government prescription mock (http://localhost:4000), reset before the tests that use it
 */
export default defineConfig({
	testDir: 'e2e',
	// One at a time: they share the pharmacy's stock, its queues and the government mock.
	workers: 1,
	fullyParallel: false,
	timeout: 90_000,
	expect: { timeout: 20_000 },
	reporter: [['list']],
	use: {
		baseURL: process.env.WEB_URL ?? 'http://localhost:5173',
		// A phone for the patient's pages; the staff console and the board open at desktop size in their own contexts.
		viewport: { width: 390, height: 844 },
		trace: 'retain-on-failure'
	}
});
