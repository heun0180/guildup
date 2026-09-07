export default function CommunityBackLink({ communityId }) {
  const valid = /^\d+$/.test(communityId ?? "");
  return (
    <p>
      <a href={valid ? `/community-dashboard.html?communityId=${encodeURIComponent(communityId)}` : "/communities.html"}>
        {valid ? "커뮤니티 대시보드로" : "내 커뮤니티"}
      </a>
    </p>
  );
}
