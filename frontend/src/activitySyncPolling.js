import { isRequestCancelled } from "./api/requestScope.js";

export const ACTIVITY_SYNC_POLL_INTERVAL_MS = 3000;

/** GET에는 상태와 저장된 결과가 함께 있다. 이전 GET 완료 후에만 다음 요청을 예약한다. */
export function startActivitySyncPolling({
  loadActivities, onUpdate, onComplete, onError,
  schedule = (callback, delay) => window.setTimeout(callback, delay),
  clearSchedule = (timer) => window.clearTimeout(timer),
}) {
  const controller = new AbortController();
  let stopped = false;
  let timer;

  async function poll() {
    if (stopped) return;
    try {
      const latest = await loadActivities({ signal: controller.signal, cache: "no-store" });
      if (stopped) return;
      onUpdate(latest);
      if (latest.sync.status !== "SYNCING") {
        stopped = true;
        onComplete(latest.sync);
        return;
      }
    } catch (error) {
      if (stopped || isRequestCancelled(error)) return;
      if (onError(error) === false) {
        stopped = true;
        return;
      }
    }
    if (!stopped) timer = schedule(poll, ACTIVITY_SYNC_POLL_INTERVAL_MS);
  }

  timer = schedule(poll, ACTIVITY_SYNC_POLL_INTERVAL_MS);
  return () => {
    stopped = true;
    clearSchedule(timer);
    controller.abort();
  };
}
