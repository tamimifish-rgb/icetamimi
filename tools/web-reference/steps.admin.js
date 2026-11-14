// Extra states of the administrator screens: open add/edit dialogs (saved as the dialog box
// alone, like the native `shotDialog`), the special-price editor and a filled company form.
// The native side is AdminScreensTest. Reference shots:
//   ROUTES='[["/admin","adm-home"],["/admin/setup","adm-setup"],["/admin/products","adm-products"],
//     ["/admin/regions","adm-regions"],["/admin/trucks","adm-trucks"],["/admin/freezers","adm-freezers"],
//     ["/admin/customers","adm-customers"],["/admin/users","adm-users"],["/admin/company","adm-company"]]'
//     node shoot.js web-admin admin          (ROUTES on one line)
const path = require('path');
const out = process.argv[2] || 'web';

module.exports = async (page, name, shot) => {
  if (!name.startsWith('adm-')) return;
  // The resting state: nothing focused or hovered (the web focuses the first field on open).
  const rest = async () => { await page.mouse.move(0, 0); await page.evaluate(() => document.activeElement && document.activeElement.blur()); };
  const box = async (file) => {
    const dialog = page.locator('[role="dialog"]');
    await dialog.waitFor({ timeout: 5000 });
    await rest();
    // Centred with translate(-50%, -50%) the box can sit on a half pixel; pin it to the corner.
    await dialog.evaluate((el) => { el.style.translate = 'none'; el.style.transform = 'none'; el.style.top = '0px'; el.style.left = '0px'; el.style.animation = 'none'; });
    await page.waitForTimeout(700);
    await dialog.screenshot({ path: path.join(out, file + '.png') });
    await page.keyboard.press('Escape');
    await page.waitForTimeout(400);
  };
  const add = (n = 0) => page.getByRole('button', { name: 'إضافة' }).nth(n).click();
  const edit = (n = 0) => page.getByLabel('تعديل', { exact: true }).nth(n).click();

  if (name === 'adm-products') {
    await add(0); await box('adm-products-add');
    await edit(0); await box('adm-products-edit');
    await add(1); await box('adm-variants-add');
    await edit(3); await box('adm-variants-edit');
  }
  if (name === 'adm-regions') { await edit(0); await box('adm-regions-edit'); }
  if (name === 'adm-trucks') { await add(0); await box('adm-trucks-add'); await edit(0); await box('adm-trucks-edit'); }
  if (name === 'adm-freezers') {
    await edit(0); await box('adm-freezers-edit');
    await add(1); await box('adm-models-add');
  }
  if (name === 'adm-customers') {
    await page.getByLabel('تعديل بقالة النور').click(); await box('adm-customers-edit');
    await add(0); await box('adm-customers-add');
    await page.getByRole('button', { name: 'أسعار خاصة لهذا العميل' }).first().click();
    await rest();
    await shot('adm-customers-prices');
  }
  if (name === 'adm-users') {
    await page.getByRole('button', { name: 'موظف جديد' }).click(); await box('adm-users-new');
    await page.getByRole('button', { name: 'تعديل', exact: true }).first().click(); await box('adm-users-edit');
    await page.getByRole('button', { name: 'موظف جديد' }).click();
    await page.locator('[role="dialog"] input').nth(0).fill('سعيد');
    await page.locator('[role="dialog"] input').nth(1).fill('saeed');
    await page.locator('[role="dialog"] input').nth(2).fill('123');
    await page.getByLabel('إظهار الرمز').click();
    await box('adm-users-error');
  }
  if (name === 'adm-company') {
    await page.locator('#company-phone').fill('05-123456');
    await page.locator('#company-footer_note').fill('شكراً لتعاملكم معنا');
    await rest();
    await shot('adm-company-filled');
  }
};
