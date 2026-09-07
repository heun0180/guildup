export default function Avatar({ src, name, className = "member-avatar" }) {
  if (src) return <img className={className} src={src} alt="" />;
  return <span className={className} aria-hidden="true">{name?.charAt(0).toUpperCase()}</span>;
}
