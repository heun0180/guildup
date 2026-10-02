import { Navigate, useLocation, useNavigate } from "react-router-dom";
import { useCommunity } from "../community/CommunityContext.jsx";
import { pubgGames, pubgPlatform, selectedPubgGame } from "../pubgPlatform.js";

const GAME_PAGES = new Set(["/bingos.html", "/kill-competitions.html", "/team-maker.html",
  "/member-activities.html", "/member-activity.html", "/game-nickname-settings.html", "/activity-rule-settings.html"]);
const SHARED_PAGES = new Set(["/members.html", "/community-settings.html", "/integrations.html"]);

export default function PubgPlatformBar({ children }) {
  const { community } = useCommunity();
  const location = useLocation();
  const navigate = useNavigate();
  const params = new URLSearchParams(location.search);
  const games = pubgGames(community);
  const visible = GAME_PAGES.has(location.pathname) || SHARED_PAGES.has(location.pathname);
  if (!visible || params.get("discordRoles") === "true" || !games.length) return children;
  const selected = selectedPubgGame(community, params.get("communityGameId"));

  if (GAME_PAGES.has(location.pathname) && !params.get("communityGameId")) {
    params.set("communityGameId", String(selected.communityGameId));
    return <Navigate replace to={`${location.pathname}?${params}`} />;
  }

  function changePlatform(event) {
    params.set("communityGameId", event.target.value);
    params.delete("competitionId");
    navigate(`${location.pathname}?${params}`);
  }

  return <>
    <div className="pubg-platform-bar">
      <strong>{selected ? `PUBG · ${pubgPlatform(selected).label}` : "PUBG 플랫폼을 선택해 주세요."}</strong>
      {games.length > 1 && <label>플랫폼 <select aria-label="PUBG 플랫폼" value={selected?.communityGameId ?? ""} onChange={changePlatform}>
        {!selected && <option value="" disabled>선택</option>}
        {games.map((game) => <option key={game.communityGameId} value={game.communityGameId}>{pubgPlatform(game).label}</option>)}
      </select></label>}
    </div>
    {selected ? children : <section className="panel page-state">이 커뮤니티의 PUBG 플랫폼을 선택해 주세요.</section>}
  </>;
}
