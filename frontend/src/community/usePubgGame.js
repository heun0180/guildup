import { useLocation } from "react-router-dom";
import { useCommunity } from "./CommunityContext.jsx";
import { selectedPubgGame } from "../pubgPlatform.js";

export function usePubgGame() {
  const { community } = useCommunity();
  const location = useLocation();
  return selectedPubgGame(community, new URLSearchParams(location.search).get("communityGameId"));
}
