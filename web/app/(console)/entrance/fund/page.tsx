import type { Metadata } from "next";
import Link from "next/link";
import { api, API_URL, type Schemas } from "@/lib/api/client";
import { entranceAt, eur, isPeriod, monthDays, monthName, reach, shiftPeriod, thisPeriod, type EntranceAt } from "@/lib/console";

export const metadata: Metadata = { title: "Каса и фонд — Етаж" };

type Entrance = Schemas["EntranceView"];
type Disbursement = Schemas["DisbursementView"];
type Handover = Schemas["HandoverStatementView"];
type Journal = Schemas["JournalView"];

type Loaded =
  | {
      kind: "ok";
      entrance: Entrance;
      fund: Schemas["FundView"];
      operating: Schemas["OperatingAccountView"] | null | { failed: string };   // null: none registered
      handovers: Handover[] | { failed: string };
      journal: Journal | { failed: string };
    }
  | Exclude<EntranceAt, { kind: "ok" }>
  | { kind: "nofund"; entrance: Entrance }
  | { kind: "refused"; entrance: Entrance; message: string };

const refusal = (r: { error?: { error?: string }; response: Response }) => r.error?.error ?? `HTTP ${r.response.status}`;

/**
 * One screen, one server-side aggregation (ADR-011): the entrance's repair fund — its account (PM-FUND-004),
 * balance, committed and available (PM-FUND-009), every disbursement signed off against it (PM-FUND-006…008) —
 * the handover statements issued for it (PM-FUND-010), the operating account it must stay apart from, and the
 * entrance's journal for a month (PM-FUND-005, PM-PMC-008). Every figure is the API's; the page sums a journal's
 * legs for display and decides nothing. The operating account, the statements and the journal each fail alone — the
 * fund stays on screen; without the fund there is nothing to show.
 */
async function load(entranceId: string | undefined, period: string, account: string | undefined): Promise<Loaded> {
  const at = await entranceAt(entranceId);
  if (at.kind !== "ok") return at;
  const { entrance } = at;

  const path = { entranceId: entrance.id };
  const [fund, operating, handovers, journal] = await Promise.all([
    reach(() => api.GET("/api/money/entrances/{entranceId}/fund", { params: { path } })),
    reach(() => api.GET("/api/money/entrances/{entranceId}/operating-account", { params: { path } })),
    reach(() => api.GET("/api/money/entrances/{entranceId}/fund/handover-statements", { params: { path } })),
    reach(() => api.GET("/api/money/entrances/{entranceId}/journal", { params: { path, query: { ...monthDays(period), ...(account && { account }) } } })),
  ]);
  if (!fund) return { kind: "down" };
  if (!fund.data) {
    // The API's 404 names PM-FUND-001 when the entrance has no fund account; any other refusal is shown as it came.
    const message = refusal(fund);
    return fund.response.status === 404 && message.includes("PM-FUND-001") ? { kind: "nofund", entrance } : { kind: "refused", entrance, message };
  }
  return {
    kind: "ok",
    entrance,
    fund: fund.data,
    // The API's 404 says so when the entrance has no operating account; any other refusal is shown as it came.
    operating: operating?.data
      ?? (operating?.response.status === 404 && refusal(operating).includes("no operating account") ? null
        : { failed: operating ? `API отказа оперативната сметка: ${refusal(operating)}` : "Бекендът не подаде оперативната сметка." }),
    handovers: handovers?.data
      ?? { failed: handovers ? `API отказа отчетите: ${refusal(handovers)}` : "Бекендът не подаде отчетите." },
    journal: journal?.data
      ?? { failed: journal ? `API отказа дневника: ${refusal(journal)}` : "Бекендът не подаде дневника." },
  };
}

/** What a disbursement may be for (PM-FUND-006), the API's purpose codes in words. */
const PURPOSE: Record<string, string> = {
  WORKS: "работи и оборудване (чл. 48–49)",
  PASSPORT_MEASURE: "мярка по техническия паспорт",
  GA_PURPOSE: "друга цел, решена от ОС",
};

const STATUS: Record<string, { label: string; many: string; tone: string }> = {
  COMMITTED: { label: "Поето", many: "Поети", tone: "warn" },
  PAID: { label: "Платено", many: "Платени", tone: "green" },
  CANCELLED: { label: "Оттеглено", many: "Оттеглени", tone: "calm" },
};

const FILTERS = [
  { key: "", label: "Всички" },
  ...Object.entries(STATUS).map(([status, { many }]) => ({ key: status.toLowerCase(), label: many })),
];

/** The journal narrowed to one account, through the API's own filter. The keys are the screen's; the accounts are the ledger's. */
const ACCOUNTS = [
  { key: "", label: "Всички сметки", account: undefined },
  { key: "fund", label: "Сметка на фонда", account: "BANK:REPAIR_RENEWAL" },
  { key: "operating", label: "Оперативна сметка", account: "BANK:OPERATING" },
  { key: "cash", label: "Каса", account: "CASH" },
];

/** The ledger's accounts in words; one the screen does not know is shown as the ledger names it. */
const ACCOUNT: Record<string, string> = {
  RECEIVABLE: "Вземания от обекти",
  ADVANCE: "Аванси от обекти",
  CASH: "Каса",
  "BANK:REPAIR_RENEWAL": "Банка · сметка на фонда",
  "BANK:OPERATING": "Банка · оперативна сметка",
  "INCOME:MANAGEMENT": "Приход · управление",
  "INCOME:MAINTENANCE": "Приход · поддръжка на общи части",
  "INCOME:REPAIR_FUND": "Приход · фонд „Ремонт“",
  "EXPENSE:REPAIR_FUND": "Разход · фонд „Ремонт“",
};

/** What wrote a journal, as the API says it; one nothing claims has no name. */
const SOURCE: Record<string, string> = { CHARGE_RUN: "Начисление", PAYMENT: "Плащане", FUND_PAYOUT: "Изплащане от фонда" };

/**
 * A journal's legs as lines: one per account and side, in the order the API gives them — its debits, then its
 * credits. A credit is below zero, as the API has it; a leg of nothing stands with the debits, where the API puts it.
 */
const lines = (legs: Schemas["JournalLeg"][]) => {
  const byLine = new Map<string, { account: string; debit: boolean; amountMinor: number; legs: number }>();
  for (const leg of legs) {
    const debit = leg.amountMinor >= 0;
    const key = `${debit}:${leg.account}`;
    const line = byLine.get(key) ?? { account: leg.account, debit, amountMinor: 0, legs: 0 };
    byLine.set(key, { ...line, amountMinor: line.amountMinor + Math.abs(leg.amountMinor), legs: line.legs + 1 });
  }
  return [...byLine.values()];
};

const date = (iso?: string) => (iso ? iso.slice(0, 10).split("-").reverse().join(".") : "—");
const iban = (value: string) => value.replace(/\s+/g, "").replace(/(.{4})(?=.)/g, "$1 ");
const purpose = (d: { purpose: string }) => PURPOSE[d.purpose] ?? d.purpose;

/** Its basis: the GA decision (PM-FUND-007), or an emergency and its written justification (PM-FUND-008). */
const basis = (d: Pick<Disbursement, "decisionId" | "emergencyJustification" | "passportMeasure">) =>
  [
    d.decisionId ? `решение ${d.decisionId}` : `аварийно, без решение: ${d.emergencyJustification ?? "—"}`,
    d.passportMeasure && `мярка: ${d.passportMeasure}`,
  ].filter(Boolean).join(" · ");

const closed = (d: Disbursement) =>
  d.status === "PAID" ? `платено ${date(d.paidOn)}`
    : d.status === "CANCELLED" ? `оттеглено ${date(d.cancelledOn)} · ${d.cancelReason ?? "—"}`
      : "чака плащане";

const DIM = { color: "#6B6F6C" };
const FAILED = { color: "#8E2318" };
const NOTE = { font: "400 11.5px/1 'IBM Plex Mono', monospace", color: "#6B6F6C" };

export default async function FundPage({ searchParams }: { searchParams: Promise<{ entrance?: string; status?: string; period?: string; account?: string }> }) {
  const query = await searchParams;
  const status = FILTERS.some((f) => f.key === query.status) ? query.status! : "";
  const period = isPeriod(query.period) ? query.period : thisPeriod();
  const account = ACCOUNTS.find((a) => a.key === query.account) ?? ACCOUNTS[0];
  const view = await load(query.entrance, period, account.account);
  const entrance = "entrance" in view ? view.entrance : undefined;

  return (
    <>
      <div className="topbar">
        <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
          <Link href="/portfolio" className="crumb">Портфейл /</Link>
          <span className="entrance-pill">
            <span className="name">{entrance?.label ?? "—"}</span>
          </span>
          <span className="meta">Каса и фонд · текущо състояние</span>
        </div>
        <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
          <button type="button" className="chip" disabled title="Още няма API за банкови извлечения." style={{ opacity: 0.5, cursor: "not-allowed" }}>Банково извлечение</button>
          <button type="button" className="chip active" disabled title="Разход от фонда подписва титулярят на сметката — изисква вход в системата." style={{ opacity: 0.5, cursor: "not-allowed" }}>Нов документ</button>
        </div>
      </div>

      {view.kind === "ok" ? (
        <Fund view={view} status={status} period={period} account={account.key} />
      ) : (
        <div style={{ padding: "16px 24px" }}>
          <div style={{ background: "#FFFFFF", border: "1px solid #DEDDD9", padding: "16px 18px", font: "400 13px/1.6 'IBM Plex Sans'" }}>
            {view.kind === "down" && <>Бекендът не отговаря на <code>{API_URL}</code>. Стартирайте го (<code>app/</code>) и опреснете страницата.</>}
            {view.kind === "unlisted" && <>API отказа списъка на входовете: {view.message}</>}
            {view.kind === "none" && <>Няма регистриран вход. Регистрирайте вход и обектите му в <code>registry</code>, после опреснете.</>}
            {view.kind === "unknown" && <>Входът <code>{view.id}</code> не е регистриран.</>}
            {view.kind === "nofund" && <>Входът няма регистрирана сметка на фонд „Ремонт и обновяване“ — фондът се създава от общото събрание, а парите му се държат в отделна банкова сметка (чл. 50 ЗУЕС). Регистрирайте сметката в <code>money</code>, после опреснете.</>}
            {view.kind === "refused" && <>API отказа: {view.message}</>}
          </div>
        </div>
      )}
    </>
  );
}

function Fund({ view, status, period, account: shownAccount }: { view: Extract<Loaded, { kind: "ok" }>; status: string; period: string; account: string }) {
  const { fund, entrance, operating, handovers, journal } = view;
  const account = operating && !("failed" in operating) ? operating : null;
  const committed = fund.disbursements.filter((d) => d.status === "COMMITTED");
  const shown = fund.disbursements.filter((d) => !status || d.status === status.toUpperCase());
  const count = (key: string) => fund.disbursements.filter((d) => !key || d.status === key.toUpperCase()).length;
  // every link keeps what the others chose: the register's filter, the journal's month and its account
  const link = (to: { status?: string; period?: string; account?: string }) => {
    const chosen = { status, period, account: shownAccount, ...to };
    return `?entrance=${entrance.id}${chosen.status ? `&status=${chosen.status}` : ""}&period=${chosen.period}${chosen.account ? `&account=${chosen.account}` : ""}`;
  };
  const href = (key: string) => link({ status: key });

  return (
    <div style={{ flex: 1, minHeight: 0, overflow: "auto", paddingBottom: 24 }}>
      <div style={{ padding: "16px 24px 0", display: "grid", gridTemplateColumns: "minmax(0,1fr) minmax(0,1fr)", gap: 16 }}>
        <div className="acct-card">
          <div className="acct-head">
            <span className="acct-title">Оперативна сметка</span>
            <span className="badge calm">{!account ? "—" : account.outflowsRecorded ? "Салдо от дневника" : "Само постъпления"}</span>
          </div>
          <div className="acct-iban">{account ? `${iban(account.iban)} · титуляр ${account.holderName}` : "—"}</div>
          <div className="acct-stats">
            <div>
              {/* a balance only once the API says money leaving the account is recorded too */}
              <div className="lbl">{account?.outflowsRecorded ? "Салдо" : "Постъпления по сметката"}</div>
              <div className="big" style={account ? undefined : DIM}>{account ? eur(account.balanceMinor) : "—"}</div>
            </div>
            {["Поети задължения", "Разполагаемо"].map((label) => (
              <div key={label}>
                <div className="lbl">{label}</div>
                <div className="big" style={DIM}>—</div>
              </div>
            ))}
          </div>
          <div className="acct-note" style={operating && "failed" in operating ? FAILED : undefined}>
            {operating && "failed" in operating ? operating.failed
              : operating === null ? "Входът няма регистрирана оперативна сметка."
                : operating.outflowsRecorded ? "Поети задължения и разполагаемо API не подава за оперативната сметка."
                  : "Разходите от оперативната сметка още не се записват: сумата е постъпилото по нея досега, не салдото в банката. Поети задължения и разполагаемо API не подава."}
          </div>
        </div>

        <div className="acct-card">
          <div className="acct-head">
            <span className="acct-title">Фонд „Ремонт и обновяване“</span>
            <span className="badge green">Отделна сметка · чл. 50 ЗУЕС</span>
          </div>
          <div className="acct-iban">{iban(fund.iban)} · титуляр {fund.holderName}</div>
          <div className="acct-stats">
            <div>
              <div className="lbl">Салдо</div>
              <div className="big">{eur(fund.balanceMinor)}</div>
            </div>
            <div>
              <div className="lbl">Поети, неплатени</div>
              <div className="big" style={{ fontWeight: 400, color: "#8E2318" }}>{eur(fund.committedMinor)}</div>
            </div>
            <div>
              <div className="lbl">Разполагаемо</div>
              <div className="big" style={{ fontWeight: 600, color: fund.availableMinor < 0 ? "#8E2318" : "#14584A" }}>{eur(fund.availableMinor)}</div>
            </div>
          </div>
          <div className="acct-note">
            {committed.length === 0
              ? "Няма поети неплатени разходи."
              : "Поети: " + committed.map((d) => `${purpose(d)} ${eur(d.amountMinor)} (${date(d.committedOn)})`).join(" · ")}
          </div>
        </div>
      </div>

      <div style={{ margin: "16px 24px 0", display: "flex", alignItems: "center", justifyContent: "space-between", gap: 16 }}>
        <div style={{ display: "flex", gap: 6 }}>
          {FILTERS.map((f) => (
            <Link key={f.key} href={href(f.key)} className={`chip${f.key === status ? " active" : ""}`}>{f.label} · {count(f.key)}</Link>
          ))}
        </div>
        <span style={NOTE}>Разходи от фонда</span>
      </div>

      <div className="pf-card" style={{ margin: "12px 24px 0", flex: "none" }}>
        <div className="fd-grid pf-head">
          <div>Подписан</div><div>Цел</div><div>Основание</div><div className="num">Сума</div><div>Състояние</div><div>Приключване</div>
        </div>
        {shown.length === 0 && (
          <div className="fd-grid pf-row"><div style={{ ...DIM, gridColumn: "1 / -1" }}>Няма разходи{status ? " в това състояние" : " от фонда"}.</div></div>
        )}
        {shown.map((d) => (
          <div key={d.id} className={`fd-grid pf-row${d.status === "COMMITTED" ? " flagged" : ""}`}>
            <div style={{ fontVariantNumeric: "tabular-nums" }}>{date(d.committedOn)}</div>
            <div className="ellipsis">{purpose(d)}</div>
            <div>{basis(d)}</div>
            <div className="num">{eur(d.amountMinor)}</div>
            <div><span className={`badge ${STATUS[d.status]?.tone ?? "calm"}`}>{STATUS[d.status]?.label ?? d.status}</span></div>
            <div style={DIM}>{closed(d)}</div>
          </div>
        ))}
        <div className="fd-grid pf-foot">
          <div style={{ gridColumn: "1 / -1" }}>{shown.length} {shown.length === 1 ? "разход" : "разхода"}</div>
        </div>
      </div>

      <Handovers handovers={handovers} />

      <JournalSection journal={journal} period={period} account={shownAccount} link={link} />
    </div>
  );
}

/**
 * The entrance's journal for a month (Rule: PM-FUND-005, PM-PMC-008), as the API reads it: each journal whole, its
 * debits and credits by account. Legs of one journal on one side of one account are one line with their sum;
 * nothing else is worked out here, and a journal that did not load shows no figure at all. The API serves no description or document number, so none is shown.
 */
function JournalSection({ journal, period, account, link }: {
  journal: Journal | { failed: string };
  period: string;
  account: string;
  link: (to: { period?: string; account?: string }) => string;
}) {
  const journals = "failed" in journal ? [] : journal.journals;
  const total = (debit: boolean) =>
    journals.reduce((sum, j) => sum + j.legs.filter((l) => l.amountMinor >= 0 === debit).reduce((s, l) => s + Math.abs(l.amountMinor), 0), 0);
  return (
    <>
      <div style={{ margin: "20px 24px 0", display: "flex", alignItems: "center", justifyContent: "space-between", gap: 16, flexWrap: "wrap" }}>
        <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
          <span className="acct-title">Дневник · {monthName(period)}</span>
          <Link href={link({ period: shiftPeriod(period, -1) })} className="chip">◀ {monthName(shiftPeriod(period, -1)).split(" ")[0]}</Link>
          <Link href={link({ period: shiftPeriod(period, 1) })} className="chip">{monthName(shiftPeriod(period, 1)).split(" ")[0]} ▶</Link>
        </div>
        <div style={{ display: "flex", gap: 6 }}>
          {ACCOUNTS.map((a) => (
            <Link key={a.key} href={link({ account: a.key })} className={`chip${a.key === account ? " active" : ""}`}>{a.label}</Link>
          ))}
        </div>
      </div>
      <div className="pf-card" style={{ margin: "8px 24px 0", flex: "none" }}>
        <div className="jr-grid pf-head">
          <div>Дата</div><div>Вид</div><div>Сметка</div><div className="num">Дебит</div><div className="num">Кредит</div>
        </div>
        {"failed" in journal ? (
          <div className="jr-grid pf-row"><div style={{ ...FAILED, gridColumn: "1 / -1" }}>{journal.failed}</div></div>
        ) : journals.length === 0 ? (
          <div className="jr-grid pf-row"><div style={{ ...DIM, gridColumn: "1 / -1" }}>Няма статии в дневника за {monthName(period)}{account ? " по тази сметка" : ""}.</div></div>
        ) : (
          journals.map((j) => (
            <div key={j.journalId} className="jr-block">
              {lines(j.legs).map((line, i) => (
                <div key={`${line.debit}:${line.account}`} className="jr-grid pf-row">
                  <div style={{ fontVariantNumeric: "tabular-nums" }}>{i === 0 ? date(j.valueDate) : ""}</div>
                  <div title={j.journalId}>{i === 0 ? (j.source ? SOURCE[j.source] ?? j.source : "—") : ""}</div>
                  <div style={line.debit ? undefined : { paddingLeft: 16 }}>
                    {ACCOUNT[line.account] ?? line.account}{line.legs > 1 && <span style={DIM}> · {line.legs} записвания</span>}
                  </div>
                  <div className="num">{line.debit ? eur(line.amountMinor) : ""}</div>
                  <div className="num">{line.debit ? "" : eur(line.amountMinor)}</div>
                </div>
              ))}
            </div>
          ))
        )}
        <div className="jr-grid pf-foot">
          {"failed" in journal ? (
            <div style={{ gridColumn: "1 / -1" }}>Дневникът не е зареден.</div>
          ) : (
            <>
              <div style={{ gridColumn: "1 / 4" }}>{journals.length} {journals.length === 1 ? "статия" : "статии"} · всяка с равни дебит и кредит</div>
              <div className="num">{eur(total(true))}</div>
              <div className="num">{eur(total(false))}</div>
            </>
          )}
        </div>
      </div>
    </>
  );
}

/**
 * The fund's handover statements as issued, newest first (PM-FUND-010) — the API checks each against its hash.
 * The parties' signatures are not recorded yet, so nothing here calls a statement signed or agreed.
 */
function Handovers({ handovers }: { handovers: Handover[] | { failed: string } }) {
  return (
    <>
      <div style={{ margin: "20px 24px 0", display: "flex", alignItems: "center", justifyContent: "space-between", gap: 16 }}>
        <span className="acct-title">Отчети при предаване на фонда</span>
        <span style={NOTE}>при смяна на управителя · подписите на страните още не се записват</span>
      </div>
      <div className="pf-card" style={{ margin: "8px 24px 0", flex: "none" }}>
        <div className="ho-grid pf-head">
          <div>Предаване</div><div>Период</div><div>Начално + постъпления − изплатени = крайно</div>
          <div className="num">Банка</div><div className="num" title="салдото в банката минус крайното по книгата">Разлика</div><div>Сверка</div><div className="num">Наследени</div>
        </div>
        {"failed" in handovers ? (
          <div className="ho-grid pf-row"><div style={{ ...FAILED, gridColumn: "1 / -1" }}>{handovers.failed}</div></div>
        ) : handovers.length === 0 ? (
          <div className="ho-grid pf-row"><div style={{ ...DIM, gridColumn: "1 / -1" }}>Няма издадени отчети при предаване.</div></div>
        ) : (
          handovers.map(({ id, statement: s, basisHash, lawVersion, engineVersion }) => {
            const movement = `${eur(s.openingMinor)} + ${eur(s.receivedMinor)} − ${eur(s.paidOutMinor)} = ${eur(s.closingMinor)}`;
            return (
              <div key={id} className={`ho-block${s.reconciled ? "" : " flagged"}`}>
                <div className="ho-grid pf-row">
                  <div style={{ fontVariantNumeric: "tabular-nums" }}>{date(s.handoverOn)}</div>
                  <div style={{ fontVariantNumeric: "tabular-nums" }}>{s.from ? `${date(s.from)} – ${date(s.handoverOn)}` : `до ${date(s.handoverOn)}`}</div>
                  <div style={{ fontVariantNumeric: "tabular-nums", lineHeight: 1.2 }}>{movement}</div>
                  <div className="num">{eur(s.bankBalanceMinor)}</div>
                  <div className="num" style={s.differenceMinor === 0 ? undefined : { color: "#8E2318" }}>{eur(s.differenceMinor)}</div>
                  <div><span className={`badge ${s.reconciled ? "green" : "crit"}`}>{s.reconciled ? "Съвпада с банката" : "Разлика с банката"}</span></div>
                  <div className="num">{s.inherited.length} · {eur(s.committedMinor)}</div>
                </div>
                <div className="ho-grid ho-note">
                  <div>разполагаемо след поетите {eur(s.availableMinor)} · издаден {date(s.issuedOn)} · закон {lawVersion} · двигател {engineVersion} · хеш на основата <span className="mono">{basisHash}</span></div>
                </div>
                {s.inherited.map((d) => (
                  <div key={d.disbursementId} className="ho-grid ho-note">
                    <div>наследено: {purpose(d)} · {basis(d)} · {eur(d.amountMinor)} · поето на {date(d.committedOn)}</div>
                  </div>
                ))}
              </div>
            );
          })
        )}
      </div>
    </>
  );
}
