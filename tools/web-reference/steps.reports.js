// Extra states of the reports and route-history pages, as the native ReportsScreensTest
// captures them (same names). Run with
//   ROUTES='[["/reports","reports-day"],["/map","map-list"],["/map?trip=rep-trip-a","map-trip"]]' node shoot.js web-reports admin
//   H=1700 ROUTES='[["/map?trip=rep-trip-a","map-trip-tall"],["/map?trip=33333333-3333-3333-3333-333333333333","map-open-tall"]]' node shoot.js web-reports admin
const TABS = [
  ['truck', 'الشاحنة'], ['region', 'المنطقة'], ['product', 'المنتج'], ['customer', 'العميل'], ['debts', 'الديون'],
  ['freezers', 'الثلاجات'], ['stock', 'فروقات المخزون'], ['profit', 'الأرباح'], ['expenses', 'المصروفات والصيانة'],
  ['inactive', 'عملاء غير نشطين'], ['visits', 'لم تتم زيارتهم اليوم'],
];
module.exports = async (page, name, shot) => {
  // A phone has no pointer resting on the page: park the mouse so nothing is drawn hovered.
  const rest = () => page.mouse.move(2, 400);
  if (name === 'reports-day') {
    // The value of a bar, as a tap on it shows.
    await page.mouse.move(133, 450); await shot('reports-tip'); await rest();
    for (const [value, label] of TABS) {
      const tab = page.getByRole('button', { name: label, exact: true });
      // The smallest scroll that shows the tab, like the native test's performScrollTo.
      await tab.evaluate((el) => el.scrollIntoView({ block: 'nearest', inline: 'nearest' }));
      await tab.click(); await rest();
      if (value === 'visits') { await shot('reports-visits-none'); await page.getByLabel('شاحنة الزيارات').selectOption({ label: 'شاحنة ٢' }); }
      await shot('reports-' + value);
      if (value === 'inactive') { await page.getByLabel('مدة عدم الشراء').selectOption('7'); await shot('reports-inactive-7'); }
    }
  }
  if (name === 'map-trip') {
    // The list of map layers behind the control at the top right of the map.
    await page.locator('.leaflet-control-layers').hover(); await shot('map-trip-layers'); await rest();
  }
};
