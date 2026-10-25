import { createApp } from './app.js';

const port = Number(process.env.PORT ?? 4000);

const server = createApp({ log: (line) => console.log(`${new Date().toISOString()} ${line}`) });

server.listen(port, () => {
	console.log(`Government prescription system (mock) on http://localhost:${port}`);
	console.log('Try GOV-1001, GOV-1002, GOV-1003 (more than the pharmacy has in stock) or GOV-1004 (already used).');
});

for (const signal of ['SIGINT', 'SIGTERM']) {
	process.on(signal, () => server.close(() => process.exit(0)));
}
