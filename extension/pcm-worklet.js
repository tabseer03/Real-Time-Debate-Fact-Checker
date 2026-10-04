// AudioWorklet: runs on the audio rendering thread.
// Takes Float32 input at the context's native rate (usually 44.1k/48k),
// mixes to mono, resamples to 16 kHz with linear interpolation,
// converts to Int16 little-endian, and posts fixed-size chunks.

class PcmWorklet extends AudioWorkletProcessor {
  constructor(options) {
    super();
    const opts = options.processorOptions || {};
    this.targetRate = opts.targetSampleRate || 16000;
    this.ratio = sampleRate / this.targetRate; // `sampleRate` is a worklet global
    this.chunkSamples = Math.round(this.targetRate * (opts.chunkMs || 100) / 1000);

    this.out = new Int16Array(this.chunkSamples);
    this.outIndex = 0;

    // Resampler state carried across process() calls.
    this.pos = 0;          // fractional read position into the current input block
    this.lastSample = 0;   // last input sample of the previous block, for interpolation
  }

  process(inputs) {
    const input = inputs[0];
    if (!input || input.length === 0) return true;

    // Mix down to mono.
    const n = input[0].length;
    const mono = new Float32Array(n);
    for (let ch = 0; ch < input.length; ch++) {
      const data = input[ch];
      for (let i = 0; i < n; i++) mono[i] += data[i];
    }
    if (input.length > 1) {
      for (let i = 0; i < n; i++) mono[i] /= input.length;
    }

    // Linear-interpolation resample. Index -1 refers to lastSample.
    while (this.pos < n) {
      const i0 = Math.floor(this.pos);
      const frac = this.pos - i0;
      const a = i0 === 0 ? this.lastSample : mono[i0 - 1];
      const b = mono[i0];
      const s = a + (b - a) * frac;

      const clamped = Math.max(-1, Math.min(1, s));
      this.out[this.outIndex++] = clamped < 0 ? clamped * 0x8000 : clamped * 0x7fff;

      if (this.outIndex === this.chunkSamples) {
        // Transfer a copy so we can keep reusing our buffer.
        const chunk = this.out.slice().buffer;
        this.port.postMessage(chunk, [chunk]);
        this.outIndex = 0;
      }
      this.pos += this.ratio;
    }
    this.pos -= n;
    this.lastSample = mono[n - 1];

    return true;
  }
}

registerProcessor('pcm-worklet', PcmWorklet);
