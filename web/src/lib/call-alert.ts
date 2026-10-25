/** "It's your turn": vibrate, and beep if the patient allowed sound. */
export class CallAlert {
	private audio: AudioContext | null = null;

	/** Browsers only allow sound after a tap, so this must be called from a click handler. */
	enableSound() {
		this.audio ??= new AudioContext();
		void this.audio.resume();
		this.beep(1);
	}

	disableSound() {
		void this.audio?.close();
		this.audio = null;
	}

	alert() {
		navigator.vibrate?.([300, 150, 300, 150, 600]);
		this.beep(3);
	}

	private beep(times: number) {
		const audio = this.audio;
		if (!audio) return;
		for (let i = 0; i < times; i++) {
			const start = audio.currentTime + i * 0.45;
			const osc = audio.createOscillator();
			const gain = audio.createGain();
			osc.frequency.value = 880;
			gain.gain.setValueAtTime(0.0001, start);
			gain.gain.exponentialRampToValueAtTime(0.4, start + 0.02);
			gain.gain.exponentialRampToValueAtTime(0.0001, start + 0.3);
			osc.connect(gain).connect(audio.destination);
			osc.start(start);
			osc.stop(start + 0.32);
		}
	}
}
