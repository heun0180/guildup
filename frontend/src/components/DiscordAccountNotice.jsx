import AppLink from "./AppLink.jsx";
import Icon from "./Icon.jsx";

export default function DiscordAccountNotice() {
  return <aside className="panel discord-account-notice" aria-labelledby="discord-account-notice-title">
    <div><h2 id="discord-account-notice-title">Discord 계정이 연결되어 있지 않습니다.</h2>
      <p>Discord 기능을 사용하려면 계정을 연결해 주세요. 현재 GuildUp 계정과 기록은 그대로 유지됩니다.</p></div>
    <AppLink className="button-link" href="/account.html"><Icon name="discord" size={18} />Discord 연결</AppLink>
  </aside>;
}
