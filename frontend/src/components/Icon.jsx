const paths = {
  dashboard: <><rect x="3" y="3" width="7" height="7" rx="2"/><rect x="14" y="3" width="7" height="7" rx="2"/><rect x="3" y="14" width="7" height="7" rx="2"/><rect x="14" y="14" width="7" height="7" rx="2"/></>,
  users: <><path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M22 21v-2a4 4 0 0 0-3-3.87M16 3.13a4 4 0 0 1 0 7.75"/></>,
  discord: <><path d="M8.5 8.5a7.8 7.8 0 0 1 7 0M7 17.5c3.4 2 6.6 2 10 0"/><path d="M6.2 5.5c-2.2 3-3.1 6.1-3.2 9.7 2.2 2.1 4.2 3.1 6.1 3.7l1.1-1.6M17.8 5.5c2.2 3 3.1 6.1 3.2 9.7-2.2 2.1-4.2 3.1-6.1 3.7l-1.1-1.6"/><circle cx="8.5" cy="13" r="1" fill="currentColor" stroke="none"/><circle cx="15.5" cy="13" r="1" fill="currentColor" stroke="none"/></>,
  game: <><path d="M6 9h12a4 4 0 0 1 3.8 5.2l-1.1 3.4a2 2 0 0 1-3.3.8L15 16H9l-2.4 2.4a2 2 0 0 1-3.3-.8l-1.1-3.4A4 4 0 0 1 6 9z"/><line x1="8" y1="12" x2="8" y2="16"/><line x1="6" y1="14" x2="10" y2="14"/><circle cx="16.5" cy="13" r=".6" fill="currentColor" stroke="none"/><circle cx="18.5" cy="15" r=".6" fill="currentColor" stroke="none"/></>,
  activity: <polyline points="22 12 18 12 15 21 9 3 6 12 2 12"/>,
  calendar: <><rect x="3" y="5" width="18" height="16" rx="2"/><line x1="16" y1="3" x2="16" y2="7"/><line x1="8" y1="3" x2="8" y2="7"/><line x1="3" y1="11" x2="21" y2="11"/></>,
  settings: <><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .34 1.88l.06.06-2.83 2.83-.06-.06a1.7 1.7 0 0 0-1.88-.34 1.7 1.7 0 0 0-1 1.55V21h-4v-.08A1.7 1.7 0 0 0 9 19.37a1.7 1.7 0 0 0-1.88.34l-.06.06-2.83-2.83.06-.06A1.7 1.7 0 0 0 4.63 15a1.7 1.7 0 0 0-1.55-1H3v-4h.08A1.7 1.7 0 0 0 4.63 9a1.7 1.7 0 0 0-.34-1.88l-.06-.06 2.83-2.83.06.06A1.7 1.7 0 0 0 9 4.63a1.7 1.7 0 0 0 1-1.55V3h4v.08a1.7 1.7 0 0 0 1 1.55 1.7 1.7 0 0 0 1.88-.34l.06-.06 2.83 2.83-.06.06A1.7 1.7 0 0 0 19.37 9a1.7 1.7 0 0 0 1.55 1H21v4h-.08A1.7 1.7 0 0 0 19.4 15z"/></>,
  plus: <><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></>,
  arrow: <><line x1="5" y1="12" x2="19" y2="12"/><polyline points="13 6 19 12 13 18"/></>,
  search: <><circle cx="11" cy="11" r="7"/><line x1="20" y1="20" x2="16.65" y2="16.65"/></>,
  check: <polyline points="20 6 9 17 4 12"/>,
  link: <><path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"/><path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"/></>,
};

export default function Icon({ name, size = 20, className = "" }) {
  return (
    <svg className={className} width={size} height={size} viewBox="0 0 24 24" fill="none"
         stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"
         aria-hidden="true">
      {paths[name]}
    </svg>
  );
}
