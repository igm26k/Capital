# Minimal Fintech mobile interface

The web and Android interfaces use Variant 1: flat navy surfaces, lavender selection and primary actions, green income, muted red expenses and blue transfers. Light and dark appearances share semantic tokens; the profile settings allow a system, light or dark appearance.

Operations have a compact search/menu heading, period/account filters, per-currency summary, date groups, expandable actions, and a floating create button. The summary and daily totals include all matching pages (the complete local projection on Android), include pending transactions, and exclude own-account transfers, opening balances and adjustments. Transfer fees remain expenses; refunds reduce expenses, matching the existing reports. “Баланс периода” means income minus expenses for the filter; currencies are never added together or implicitly converted. Transfers retain both entries and currencies.

Mobile creation uses a full-screen editor, three operation tabs, scrollable fields and a pinned save action. The web editor follows the visual viewport to keep its footer above the keyboard and respects safe areas. Existing split validation, transfer/fee logic, conflict handling and idempotent retries remain in place. Android retains native persisted drafts and the offline queue.

Settings start with the profile, workspace and timezone, followed by finance, integrations/data and application groups. Logout and session renewal are available in settings. Existing management, import/export, security and reporting functions remain accessible. Bank settings distinguish Android permissions, application reception and configured rules; browsers truthfully offer manual message recognition only.

Verification: `npm run build`, `npm run typecheck:e2e`, `npm run test:mobile` in `web`; signed Android build, unit tests and both lint variants through `python3 ops/personal_signing.py --sdk /opt/accounting-sdk --verify-all`. Device and physical keyboard behavior require a real device check; no emulator is used.
