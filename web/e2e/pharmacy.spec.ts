import { expect, test, type APIRequestContext, type Browser, type Locator, type Page } from '@playwright/test';

const GOV_URL = process.env.GOV_URL ?? 'http://localhost:4000';

// The patient's phone is the default page of each test. The staff console and the waiting-room board open in contexts of
// their own, at the size of a counter screen.
const COUNTER_SCREEN = { width: 1200, height: 900 };

const unique = () => `E2E ${Math.random().toString(36).slice(2, 7)}`;

async function openOnCounterScreen(browser: Browser, path: string): Promise<Page> {
	const context = await browser.newContext({ viewport: COUNTER_SCREEN, baseURL: test.info().project.use.baseURL });
	const page = await context.newPage();
	await page.goto(path);
	return page;
}

/** The patient checks in by hand and lands on their ticket. Returns the ticket number. */
async function checkIn(phone: Page, name: string, packs: Record<string, number>): Promise<number> {
	await phone.goto('/');
	await phone.getByLabel('Your name').fill(name);
	for (const [medication, count] of Object.entries(packs)) {
		for (let i = 0; i < count; i++) await phone.getByRole('button', { name: `One pack more of ${medication}` }).click();
	}
	await phone.getByRole('button', { name: 'Get my number' }).click();
	await phone.waitForURL(/\/track\//);
	return Number(await phone.locator('.number').innerText());
}

/** The patient's row on the staff console, wherever it is now. */
const row = (staff: Page, name: string): Locator => staff.locator('li', { hasText: name });

const step = async (staff: Page, name: string, button: string | RegExp) => {
	await row(staff, name).getByRole('button', { name: button }).click();
};

const statusOf = (phone: Page): Locator => phone.getByRole('status');

/** The numbers under one heading of the waiting-room board. */
const boardSection = (board: Page, heading: RegExp): Locator => board.locator('section', { has: board.getByRole('heading', { name: heading }) });

async function packsOnShelf(staff: Page, medication: string): Promise<number> {
	const text = await staff.getByRole('row', { name: new RegExp(medication) }).getByRole('cell').nth(2).innerText();
	return Number.parseInt(text, 10);
}

/** Stops the browser's own prompt() and confirm() from blocking, answering them. */
function answerDialogsWith(page: Page, reason = 'Allergy on file') {
	page.on('dialog', (dialog) => void dialog.accept(dialog.type() === 'prompt' ? reason : undefined));
}

async function resetGovernment(request: APIRequestContext) {
	const reset = await request.post(`${GOV_URL}/admin/reset`);
	expect(reset.ok(), `the government mock must run at ${GOV_URL} (make gov-api)`).toBe(true);
}

test('a patient follows their prescription from check-in to the hand-over, and the waiting room sees only the number', async ({ page, browser }) => {
	const staff = await openOnCounterScreen(browser, '/staff');
	const board = await openOnCounterScreen(browser, '/board');
	const name = unique();

	const ticket = await checkIn(page, name, { 'Ibuprofen 400 mg': 1 });

	// The stock check is automatic.
	await expect(statusOf(page)).toContainText('Waiting for a pharmacist');
	await expect(page.getByText('Live: this page updates by itself')).toBeVisible();
	await expect(boardSection(board, /Being prepared/)).toContainText(String(ticket));
	await expect(board.locator('body')).not.toContainText(name);

	await step(staff, name, 'Approve');
	await expect(statusOf(page)).toContainText('Approved, now packing');

	await step(staff, name, 'Packed');
	await expect(statusOf(page)).toContainText("Ready! We'll call you shortly");
	await expect(boardSection(board, /Ready, we'll call you soon/)).toContainText(String(ticket));

	await step(staff, name, `Call number ${ticket}`);
	await expect(statusOf(page)).toContainText("It's your turn!");
	await expect(page).toHaveTitle(`Your turn! Number ${ticket}`);
	await expect(boardSection(board, /Now calling/)).toContainText(String(ticket));

	await step(staff, name, 'Handed over');
	await expect(statusOf(page)).toContainText('All done. Get well soon!');
	await expect(page.getByRole('link', { name: 'Start a new check-in' })).toBeVisible();
	await expect(boardSection(board, /Now calling/)).not.toContainText(String(ticket));
	expect(await page.evaluate(() => localStorage.getItem('pharmacy.activeTicket'))).toBeNull();
});

test('a patient who closes the page comes back to their ticket', async ({ page, browser }) => {
	const name = unique();
	const ticket = await checkIn(page, name, { 'Ibuprofen 400 mg': 1 });
	await expect(statusOf(page)).toContainText('Waiting for a pharmacist');
	const tracking = page.url();

	await page.goto('/');

	await expect(page.getByText(`You already have number ${ticket} in progress.`)).toBeVisible();
	await page.getByRole('link', { name: 'See its status' }).click();
	await expect(page).toHaveURL(tracking);
	await expect(page.locator('.number')).toHaveText(String(ticket));

	// Tidy up for the next test: the ticket is not left waiting for a pharmacist.
	const staff = await openOnCounterScreen(browser, '/staff');
	answerDialogsWith(staff);
	await step(staff, name, 'Reject...');
	await expect(statusOf(page)).toContainText('Please speak to a pharmacist');
});

test('a rejection tells the patient why, and to come to the counter', async ({ page, browser }) => {
	const staff = await openOnCounterScreen(browser, '/staff');
	answerDialogsWith(staff, 'Please bring your insurance card');
	const name = unique();
	const ticket = await checkIn(page, name, { 'Ibuprofen 400 mg': 1 });
	await expect(statusOf(page)).toContainText('Waiting for a pharmacist');

	await step(staff, name, 'Reject...');

	await expect(statusOf(page)).toContainText('Please speak to a pharmacist');
	await expect(statusOf(page)).toContainText(`Please bring your insurance card. Please come to the counter and mention number ${ticket}.`);
	await expect(page.getByLabel('Progress')).toHaveCount(0);
	await expect(page.getByRole('link', { name: 'Start a new check-in' })).toBeVisible();
});

test('a number nobody answers is given up, the packs go back on the shelf, and the patient can check in again', async ({ page, browser }) => {
	const staff = await openOnCounterScreen(browser, '/staff');
	answerDialogsWith(staff);
	const name = unique();
	const before = await packsOnShelf(staff, 'Ibuprofen 400 mg');

	const ticket = await checkIn(page, name, { 'Ibuprofen 400 mg': 2 });
	await expect(statusOf(page)).toContainText('Waiting for a pharmacist');
	await expect.poll(() => packsOnShelf(staff, 'Ibuprofen 400 mg')).toBe(before - 2);

	await step(staff, name, 'Approve');
	await step(staff, name, 'Packed');
	await step(staff, name, `Call number ${ticket}`);
	await expect(statusOf(page)).toContainText("It's your turn!");
	await step(staff, name, 'Call again');
	await step(staff, name, 'No show');

	await expect(statusOf(page)).toContainText("We couldn't reach you");
	await expect(page.getByRole('link', { name: 'Start a new check-in' })).toBeVisible();
	await expect.poll(() => packsOnShelf(staff, 'Ibuprofen 400 mg')).toBe(before);
});

test('the sample government prescriptions fill in the number when tapped, and the government system knows each of them', async ({ page, request }) => {
	await resetGovernment(request);
	await page.goto('/');
	const field = page.getByLabel(/Government prescription number/);

	for (const number of ['GOV-1001', 'GOV-1002', 'GOV-1003']) {
		await page.getByRole('button', { name: new RegExp(number) }).click();
		await expect(field).toHaveValue(number);
		await page.getByRole('button', { name: 'Look up' }).click();
		await expect(page.getByText(`Medication on government prescription ${number}`)).toBeVisible();
		await page.getByRole('button', { name: 'Enter my medication by hand instead' }).click();
	}

	// The one that is used in full already: the patient is told so, and the samples are still there for another try.
	await page.getByRole('button', { name: /GOV-1004/ }).click();
	await page.getByRole('button', { name: 'Look up' }).click();
	await expect(page.getByRole('alert')).toContainText('has already been used in full');
	await expect(page.getByRole('button', { name: /GOV-1001/ })).toBeVisible();
});

test('a government prescription: two phones cannot take the same packs, and what was collected is committed to the government', async ({ page, browser, request }) => {
	await resetGovernment(request);
	const staff = await openOnCounterScreen(browser, '/staff');
	const name = unique();

	// Phone one looks up the prescription and checks in with all of it: 1 amoxicillin, up to 2 ibuprofen.
	await page.goto('/');
	await page.getByLabel(/Government prescription number/).fill('gov-1001');
	await page.getByRole('button', { name: 'Look up' }).click();
	await expect(page.getByText('Medication on government prescription GOV-1001')).toBeVisible();
	await expect(page.getByText('up to 2 packs')).toBeVisible();
	await page.getByLabel('Your name').fill(name);
	await page.getByRole('button', { name: 'Get my number' }).click();
	await page.waitForURL(/\/track\//);
	const ticket = Number(await page.locator('.number').innerText());
	await expect(statusOf(page)).toContainText('Waiting for a pharmacist');

	// Phone two, with the same number, before phone one has collected anything.
	const phoneTwo = await (await browser.newContext({ viewport: { width: 390, height: 844 }, baseURL: test.info().project.use.baseURL })).newPage();
	await phoneTwo.goto('/');
	await phoneTwo.getByLabel(/Government prescription number/).fill('GOV-1001');
	await phoneTwo.getByRole('button', { name: 'Look up' }).click();
	await phoneTwo.getByLabel('Your name').fill(unique());
	await phoneTwo.getByRole('button', { name: 'Get my number' }).click();
	await expect(phoneTwo.getByRole('alert')).toContainText('already on a ticket that is still in progress');
	await expect(phoneTwo).toHaveURL(/\/$/);

	// Phone one collects. Nothing at the counter waits for the government: the commit goes from the outbox.
	await step(staff, name, 'Approve');
	await step(staff, name, 'Packed');
	await step(staff, name, `Call number ${ticket}`);
	await step(staff, name, 'Handed over');
	await expect(statusOf(page)).toContainText('All done. Get well soon!');
	await expect
		.poll(async () => (await (await request.get(`${GOV_URL}/prescriptions/GOV-1001`)).json()).items.map((i: { quantity: number }) => i.quantity), { timeout: 30_000 })
		.toEqual([0, 0]);

	// Now the government knows: phone two is told there is nothing left.
	await phoneTwo.goto('/');
	await phoneTwo.getByLabel(/Government prescription number/).fill('GOV-1001');
	await phoneTwo.getByRole('button', { name: 'Look up' }).click();
	await expect(phoneTwo.getByRole('alert')).toContainText('Government prescription GOV-1001 has already been used in full');
	await resetGovernment(request);
});

test('the check-in page will not ask for more packs than there are, nor for nothing at all', async ({ page }) => {
	await page.goto('/');
	await expect(page.getByText('Amoxicillin 500 mg')).toBeVisible();

	// Press + on a medication until the page stops it: at what is in stock.
	const more = page.getByRole('button', { name: 'One pack more of Atorvastatin 20 mg' });
	for (let presses = 0; presses < 15 && (await more.isEnabled()); presses++) await more.click();
	await expect(more).toBeDisabled();
	const packs = Number(await page.getByRole('group', { name: 'Packs of Atorvastatin 20 mg' }).locator('output').innerText());
	expect(packs).toBeGreaterThan(0);
	await page.getByRole('button', { name: 'One pack less of Atorvastatin 20 mg' }).click({ clickCount: packs });
	await expect(page.getByText('Nothing selected yet')).toBeVisible();

	await page.getByLabel('Your name').fill(unique());
	await page.getByRole('button', { name: 'Get my number' }).click();
	await expect(page.getByRole('alert')).toContainText('Please choose at least one medication from your prescription.');
	await expect(page).toHaveURL(/\/$/);
});
