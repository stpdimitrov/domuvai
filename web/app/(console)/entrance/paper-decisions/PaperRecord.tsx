import { originalOf, type PaperDecision } from "@/lib/paperDecision";

const date = (iso: string) => iso.split("-").reverse().join(".");

/** One recorded decision, on the page and in the form's answer alike: where the paper original is, always (Rule: PM-GA-022). */
// Rule: PM-GA-022, PM-DOC-007, PM-SEC-002
export default function PaperRecord({ decision }: { decision: PaperDecision }) {
  return (
    <>
      <strong>{decision.protocolRef}</strong> от {date(decision.protocolDate)} · <span style={{ whiteSpace: "pre-line" }}>{decision.decisionText}</span>
      <div>{originalOf(decision)}</div>
      <div className="as-hint">Номер на решението: <code>{decision.id}</code></div>
    </>
  );
}
