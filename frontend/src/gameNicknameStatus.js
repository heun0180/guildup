export async function loadGameNicknameStatus({ loadStatus, loadRule }) {
  try {
    return await loadStatus();
  } catch (error) {
    if (error?.status !== 404) throw error;
    const rule = await loadRule();
    return { configured: rule?.configured === true };
  }
}
