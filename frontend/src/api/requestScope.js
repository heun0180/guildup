/** Expected lifecycle cancellation; HTTP failures and timeouts remain visible. */
export const isRequestCancelled = (error) => error?.name === "AbortError";

/** Cancels old platform requests and rejects even transports that resolve after cancellation. */
export function createRequestScope(transport) {
  const pending = new Set();
  let generation = 0;
  return {
    async request(url, options = {}) {
      const controller = new AbortController();
      const startedGeneration = generation;
      const signal = options.signal
        ? AbortSignal.any([controller.signal, options.signal]) : controller.signal;
      pending.add(controller);
      try {
        const result = await transport(url, { ...options, signal });
        if (signal.aborted || generation !== startedGeneration) throw new DOMException("Request scope changed", "AbortError");
        return result;
      } finally { pending.delete(controller); }
    },
    cancel() {
      generation += 1;
      pending.forEach((controller) => controller.abort());
      pending.clear();
    },
  };
}
