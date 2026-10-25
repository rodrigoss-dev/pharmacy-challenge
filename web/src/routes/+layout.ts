// Every screen shows live data from the API, so there is nothing to render on the server:
// the app is a static single-page app that calls the backend from the browser.
export const ssr = false;
export const prerender = false;
