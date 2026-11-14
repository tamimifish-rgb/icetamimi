// Extra states of the supervisor / invoice-change screens (names start with "sup-").
// Run with the routes below; the Android side is SupervisorScreensTest.
//   H=2200 ROUTES="$(node -p 'JSON.stringify(require("./steps.supervisor.js").admin)')" node shoot.js web-supervisor admin
//   H=2200 ROUTES="$(node -p 'JSON.stringify(require("./steps.supervisor.js").supervisor)')" node shoot.js web-supervisor supervisor
//   H=2200 ROUTES="$(node -p 'JSON.stringify(require("./steps.supervisor.js").distributor)')" node shoot.js web-supervisor distributor
const blur = (page) => page.evaluate(() => document.activeElement && document.activeElement.blur());

module.exports = async (page, name, shot) => {
  if (name === 'sup-trips') {
    // The fixture's campaigns are dated 2026-11-14; show that day whatever today is.
    await page.locator('input[type="date"]').fill('2026-11-14');
    await page.getByText('شاحنة ٧').waitFor();
    await blur(page);
    await shot('sup-trips');
    // Two days earlier: that day's campaign, and every later one still open.
    await page.locator('input[type="date"]').fill('2026-11-12');
    await page.getByText('حملة 1 · ياسر بن شملان').waitFor();
    await blur(page);
    await shot('sup-trips-other-day');
  }
  if (name === 'sup-trip') {
    await page.getByRole('button', { name: 'مرتجع', exact: true }).click();
    await page.getByPlaceholder('0').first().fill('3');
    await blur(page);
    await shot('sup-trip-return');
  }
  if (name === 'sup-change-cancel') {
    await page.locator('textarea').fill('الفاتورة سُجلت على عميل خطأ');
    await blur(page);
    await shot('sup-change-cancel-filled');
  }
  if (name === 'sup-change-replace') {
    const plus = page.getByLabel('زيادة').first();
    await plus.click(); await plus.click();
    await page.getByLabel('زيادة').nth(1).click();
    await page.locator('input[inputmode="numeric"]').last().fill('3000');
    await page.locator('textarea').fill('الكمية الصحيحة ١٢ وليست ١٠');
    await blur(page);
    await shot('sup-change-replace-filled');
  }
};

module.exports.admin = [
  ['/trips', 'sup-trips'], ['/trips/sup-trip-b', 'sup-trip'], ['/trips/sup-trip-a', 'sup-trip-closed'],
  ['/trips/sup-trip-d', 'sup-trip-pending'], ['/trips/sup-trip-c', 'sup-trip-flagged'],
  ['/closings', 'sup-closings'], ['/invoice-changes', 'sup-changes'],
];
module.exports.supervisor = [['/closings', 'sup-closings-supervisor']];
module.exports.distributor = [
  ['/invoice-changes', 'sup-changes-dist'], ['/invoice-change/s0?kind=cancel', 'sup-change-cancel'],
  ['/invoice-change/s0?kind=replace', 'sup-change-replace'],
];
