// See https://svelte.dev/docs/kit/types#app.d.ts
// for information about these interfaces
declare global {
	namespace App {
		// interface Error {}
		// interface Locals {}
		// interface PageData {}
		// interface PageState {}
		// interface Platform {}
	}

	interface ImportMetaEnv {
		/** The backend's base URL, read at build time (see src/lib/api.ts). */
		readonly VITE_API_URL?: string;
	}
}

export {};
