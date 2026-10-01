/** 달력의 월 번호로 이동하므로 브라우저 시간대에 영향을 받지 않는다. */
export function shiftedRankingQuery(period, direction) {
  const monthly = period.periodType === "MONTHLY";
  const index = period.year * 12 + (monthly ? period.month - 1 : (period.quarter - 1) * 3)
    + direction * (monthly ? 1 : 3);
  const month = index % 12 + 1;
  const query = new URLSearchParams({ year: String(Math.floor(index / 12)) });
  query.set(monthly ? "month" : "quarter", String(monthly ? month : Math.floor((month - 1) / 3) + 1));
  return query.toString();
}
