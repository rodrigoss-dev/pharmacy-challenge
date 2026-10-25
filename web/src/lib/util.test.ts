import { afterEach, describe, expect, it, vi } from 'vitest';
import { activeTicket, minutesSince, plural, uuid } from './util.ts';

const realCrypto = globalThis.crypto;

afterEach(() => {
	vi.unstubAllGlobals();
	vi.useRealTimers();
});

describe('plural', () => {
	it('counts', () => {
		expect(plural(0, 'pack')).toBe('0 packs');
		expect(plural(1, 'pack')).toBe('1 pack');
		expect(plural(2, 'pack')).toBe('2 packs');
		expect(plural(2, 'person', 'people')).toBe('2 people');
		expect(plural(1, 'person', 'people')).toBe('1 person');
	});
});

describe('minutesSince', () => {
	it('rounds down, and is never negative', () => {
		vi.useFakeTimers();
		vi.setSystemTime(new Date('2026-10-25T10:10:00Z'));

		expect(minutesSince('2026-10-25T10:09:30Z')).toBe(0);
		expect(minutesSince('2026-10-25T10:07:01Z')).toBe(2);
		expect(minutesSince('2026-10-25T10:20:00Z')).toBe(0); // a clock a little behind the server's
	});
});

describe('uuid', () => {
	it('uses the browser when it can', () => {
		vi.stubGlobal('crypto', { randomUUID: () => 'from-the-browser' });

		expect(uuid()).toBe('from-the-browser');
	});

	it('makes a version 4 id where randomUUID is missing (plain http on a phone)', () => {
		vi.stubGlobal('crypto', { getRandomValues: (bytes: Uint8Array) => bytes.fill(0xff) });

		expect(uuid()).toBe('ffffffff-ffff-4fff-bfff-ffffffffffff');
	});

	it('differs every time', () => {
		vi.stubGlobal('crypto', { getRandomValues: <T extends ArrayBufferView<ArrayBuffer>>(bytes: T) => realCrypto.getRandomValues(bytes) });

		expect(new Set(Array.from({ length: 50 }, uuid)).size).toBe(50);
	});
});

describe('the ticket remembered between visits', () => {
	function storage() {
		const items = new Map<string, string>();
		return {
			getItem: (key: string) => items.get(key) ?? null,
			setItem: (key: string, value: string) => void items.set(key, value),
			removeItem: (key: string) => void items.delete(key)
		};
	}

	it('is kept, and read back', () => {
		vi.stubGlobal('localStorage', storage());

		expect(activeTicket.get()).toBeNull();
		activeTicket.set({ id: 'abc', ticketNumber: 7 });

		expect(activeTicket.get()).toEqual({ id: 'abc', ticketNumber: 7 });
	});

	it("is forgotten only when it is the one being cleared: looking at another ticket must not lose it", () => {
		vi.stubGlobal('localStorage', storage());
		activeTicket.set({ id: 'abc', ticketNumber: 7 });

		activeTicket.clear('someone-elses');
		expect(activeTicket.get()).toEqual({ id: 'abc', ticketNumber: 7 });

		activeTicket.clear('abc');
		expect(activeTicket.get()).toBeNull();
	});

	it('is not an error to have no storage (private mode) or garbage in it', () => {
		const broken = {
			getItem: () => {
				throw new Error('denied');
			},
			setItem: () => {
				throw new Error('denied');
			},
			removeItem: () => {
				throw new Error('denied');
			}
		};
		vi.stubGlobal('localStorage', broken);

		expect(activeTicket.get()).toBeNull();
		expect(() => activeTicket.set({ id: 'abc', ticketNumber: 7 })).not.toThrow();
		expect(() => activeTicket.clear('abc')).not.toThrow();

		vi.stubGlobal('localStorage', { ...storage(), getItem: () => '{not json' });
		expect(activeTicket.get()).toBeNull();
	});
});
