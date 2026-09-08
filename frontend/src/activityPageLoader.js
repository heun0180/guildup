export async function loadActivityPageData({ loadCommunity, loadNicknameStatus, loadActivities }) {
  let community;
  let nicknameStatus;
  try {
    [community, nicknameStatus] = await Promise.all([
      loadCommunity(),
      loadNicknameStatus(),
    ]);
  } catch (error) {
    error.activityLoadStage = "configuration";
    throw error;
  }

  if (!nicknameStatus?.configured) {
    return { community, status: "notConfigured", activities: null };
  }

  try {
    return {
      community,
      status: "configured",
      activities: await loadActivities(),
    };
  } catch (error) {
    error.activityLoadStage = "activities";
    throw error;
  }
}
