/** An import's status, the API's code in words (S-41c/d); the code is shown beside it, and an unknown one as it came. */
const STATUS: Record<string, string> = {
  REPRODUCED: "Възпроизведено до стотинка",
  NEEDS_REVIEW: "Не е възпроизведено — за преглед",
  COMMITTING: "Приема се",
  COMMITTED: "Прието в регистъра",
  COMMIT_BLOCKED: "Приемането е спряно",
  REVERTING: "Отменя се",
  REVERTED: "Отменено",
  REVERT_BLOCKED: "Отмяната е спряна",
};
export const statusOf = (code: string) => (STATUS[code] ? `${STATUS[code]} (${code})` : code);
