/**
 * Browser verification harness.
 *
 * Signs in as admin against the running dev server and walks every route,
 * collecting `pageerror` (uncaught exceptions - the CRITICAL findings blanked
 * the SPA this way) and console errors per route. Exits non-zero if any route
 * produced a page error.
 *
 * Usage: node scripts/verify-routes.mjs <customerId> <assessmentId> [baseUrl]
 */
import { chromium } from 'playwright'
import { mkdirSync } from 'node:fs'

const [customerId, assessmentId, baseUrl = 'http://localhost:5173'] = process.argv.slice(2)
if (!customerId || !assessmentId) {
  console.error('usage: node scripts/verify-routes.mjs <customerId> <assessmentId> [baseUrl]')
  process.exit(2)
}

const SHOTS = '/tmp/caa-verify'
mkdirSync(SHOTS, { recursive: true })

/** Console noise that is not an application error. */
const IGNORED_CONSOLE = [/Download the React DevTools/i, /\[vite\] connect(ed|ing)/i]

const results = []

async function main() {
  const browser = await chromium.launch()
  const context = await browser.newContext({ viewport: { width: 1600, height: 1000 } })
  const page = await context.newPage()

  let current = { name: 'boot', pageErrors: [], consoleErrors: [] }

  page.on('pageerror', (error) => {
    current.pageErrors.push(`${error.name}: ${error.message}`)
  })
  page.on('console', (message) => {
    if (message.type() !== 'error') return
    const text = message.text()
    if (IGNORED_CONSOLE.some((pattern) => pattern.test(text))) return
    current.consoleErrors.push(text)
  })

  // ---- sign in ----------------------------------------------------------
  await page.goto(`${baseUrl}/login`, { waitUntil: 'networkidle' })
  await page.locator('input[name="username"]').fill('admin')
  await page.locator('input[name="password"]').fill('admin123')
  await page.getByRole('button', { name: /sign in/i }).click()
  await page.waitForURL(/\/dashboard/, { timeout: 20000 })
  await page.waitForLoadState('networkidle')
  console.log('signed in as admin ->', page.url())

  /**
   * @param {string} name
   * @param {string} path
   * @param {(page: import('playwright').Page) => Promise<string[]>} [interact]
   *        returns human-readable observations
   */
  async function visit(name, path, interact) {
    current = { name, path, pageErrors: [], consoleErrors: [], observed: [] }
    results.push(current)
    try {
      await page.goto(`${baseUrl}${path}`, { waitUntil: 'networkidle', timeout: 45000 })
      await page.waitForTimeout(1200)
      if (interact) current.observed = await interact(page)
      // A crash panel is a caught error, but still a failed screen.
      const crashed = await page.getByText('This screen could not be displayed').count()
      if (crashed > 0) current.pageErrors.push('RouteErrorBoundary rendered the crash panel')
    } catch (error) {
      current.pageErrors.push(`navigation/interaction failed: ${error.message}`)
    }
    await page.screenshot({ path: `${SHOTS}/${name}.png`, fullPage: true })
    const status = current.pageErrors.length === 0 ? 'OK  ' : 'FAIL'
    console.log(`${status} ${name.padEnd(22)} ${path}`)
    for (const line of current.observed) console.log(`       ${line}`)
    for (const error of current.pageErrors) console.log(`       PAGEERROR: ${error}`)
    for (const error of current.consoleErrors) console.log(`       console.error: ${error}`)
  }

  const textOf = async (locator) => (await locator.count()) > 0 ? (await locator.first().innerText()).replace(/\s+/g, ' ').trim() : '<absent>'

  await visit('dashboard', '/dashboard', async (p) => {
    const rows = p.locator('table tbody tr')
    const observations = [`customer rows: ${await rows.count()}`]
    const first = rows.first()
    const cells = first.locator('td')
    const values = []
    for (let i = 0; i < (await cells.count()); i++) {
      values.push((await cells.nth(i).innerText()).replace(/\s+/g, ' ').trim())
    }
    observations.push(`row 1 cells: ${JSON.stringify(values)}`)
    observations.push(`em dashes in row 1: ${values.filter((v) => v.includes('—')).length}`)
    /* Paging: 12 seeded customers fit on one default page, so shrink the page
       size first. Before the `toPage` fix the widget always reported page 0,
       which left Previous permanently disabled and Next a no-op. */
    await p.getByLabel('Rows per page').selectOption('10')
    await p.waitForTimeout(1200)
    const next = p.getByRole('button', { name: /next page/i }).first()
    const prev = p.getByRole('button', { name: /previous page/i }).first()
    const range = p.locator('text=/of 12/').first()
    observations.push(`page 1 range: ${await textOf(range)}`)
    observations.push(`previous disabled on page 1: ${!(await prev.isEnabled())}`)
    const before = await rows.first().innerText()
    await next.click()
    await p.waitForTimeout(1500)
    const after = await rows.first().innerText()
    observations.push(`page 2 range: ${await textOf(range)}`)
    observations.push(`next changed the first row: ${before !== after}`)
    observations.push(`previous enabled on page 2: ${await prev.isEnabled()}`)
    await prev.click()
    await p.waitForTimeout(1500)
    observations.push(`previous returned to page 1: ${(await rows.first().innerText()) === before}`)
    await p.getByLabel('Rows per page').selectOption('20')
    await p.waitForTimeout(1000)
    return observations
  })

  await visit('customer', `/customers/${customerId}`, async (p) => {
    const observations = []
    const tiles = p.locator('dl,[class*="StatCard"], div').filter({ hasText: /^$/ })
    void tiles
    for (const label of ['Transactions', 'Total amount', 'Failed / reversed', 'Counterparty countries']) {
      const tile = p.locator('div').filter({ hasText: new RegExp(`^${label}`) }).last()
      observations.push(`tile "${label}": ${await textOf(tile)}`)
    }
    const velocity = p.getByText('Velocity and exposure')
    observations.push(`velocity card present: ${(await velocity.count()) > 0}`)
    if (await velocity.count()) {
      const card = velocity.locator('xpath=ancestor::*[self::section or self::div][2]')
      observations.push(`velocity: ${(await card.innerText()).replace(/\s+/g, ' ').slice(0, 320)}`)
    }
    // Activity tabs must carry real counts, not 0.
    const tabs = p.getByRole('tab')
    const tabTexts = []
    for (let i = 0; i < (await tabs.count()); i++) {
      tabTexts.push((await tabs.nth(i).innerText()).replace(/\s+/g, ' ').trim())
    }
    observations.push(`activity tabs: ${JSON.stringify(tabTexts)}`)
    return observations
  })

  await visit('analysis', `/analyses/${assessmentId}`, async (p) => {
    const observations = []
    observations.push(`risk headline: ${await textOf(p.getByText(/CRITICAL|HIGH|MEDIUM|LOW/).first())}`)
    const rows = p.locator('table tbody tr')
    observations.push(`coverage table rows: ${await rows.count()}`)
    // Expand a rule row - this threw TypeError before the fix.
    const triggered = rows.filter({ hasText: /\+/ })
    const target = (await triggered.count()) > 0 ? triggered.first() : rows.first()
    const scoreText = (await target.innerText()).replace(/\s+/g, ' ').trim()
    observations.push(`row before expand: ${scoreText.slice(0, 200)}`)
    await target.click()
    await p.waitForTimeout(1200)
    observations.push(`expanded panel text: ${(await p.locator('table').innerText()).replace(/\s+/g, ' ').slice(0, 400)}`)
    // Expand every row, since only some carry matched transactions.
    for (let i = 0; i < Math.min(await rows.count(), 14); i++) {
      await rows.nth(i).click()
      await p.waitForTimeout(120)
    }
    observations.push('expanded every coverage row')
    return observations
  })

  await visit('analyses', '/analyses', async (p) => [
    `history rows: ${await p.locator('table tbody tr').count()}`,
  ])

  await visit('knowledge-search', '/knowledge-search', async (p) => {
    const observations = []
    const input = p.getByLabel(/Search the policy knowledge base/i).first()
    await input.fill('reporting threshold for large payments')
    await input.press('Enter')
    await p.waitForSelector('section[aria-label="Search results"]', { timeout: 30000 })
    await p.waitForTimeout(2500)
    const section = p.locator('section[aria-label="Search results"]')
    observations.push(`results: ${(await section.innerText()).replace(/\s+/g, ' ').slice(0, 460)}`)
    return observations
  })

  await visit('admin-rules', '/admin/rules', async (p) => {
    const observations = [`rule rows: ${await p.locator('table tbody tr').count()}`]
    // Open the editor modal on a rule that uses a non-EQ operator.
    const edit = p.getByRole('button', { name: /^Edit/ })
    await edit.first().click()
    await p.waitForTimeout(1500)
    const dialog = p.getByRole('dialog')
    observations.push(`editor modal open: ${(await dialog.count()) > 0}`)
    observations.push(`condition rows: ${await dialog.getByRole('group').count()}`)

    /* The operator dropdown rendered ZERO options before the fix, and changing
       a field silently rewrote the operator to EQ. Pick the row that carries a
       NON-EQ operator so the preservation check is meaningful. */
    const fields = dialog.getByLabel('Field', { exact: true })
    const operators = dialog.getByLabel('Operator', { exact: true })
    let index = 0
    for (let i = 0; i < (await operators.count()); i++) {
      if ((await operators.nth(i).inputValue()) !== 'EQ') { index = i; break }
    }
    const fieldSelect = fields.nth(index)
    const operatorSelect = operators.nth(index)
    const opts = await operatorSelect.locator('option').allInnerTexts()
    const beforeField = await fieldSelect.inputValue()
    const before = await operatorSelect.inputValue()
    observations.push(`row ${index + 1} is "${beforeField} ${before}"`)
    observations.push(`its operator dropdown offers ${opts.length}: ${JSON.stringify(opts)}`)
    const booleanOperators = await operators
      .nth([...Array(await operators.count()).keys()].find(() => true) ?? 0)
      .locator('option')
      .count()
    void booleanOperators

    const values = await fieldSelect.locator('option').evaluateAll((os) => os.map((o) => o.value))
    const target = values.find((v) => v.startsWith('agg.') && v !== beforeField)
    if (target) {
      await fieldSelect.selectOption(target)
      await p.waitForTimeout(900)
      const after = await operatorSelect.inputValue()
      observations.push(
        `changed field ${beforeField} -> ${target}: operator ${before} -> ${after} (PRESERVED: ${before === after})`,
      )
    }
    const preview = dialog.locator('pre').first()
    if (await preview.count()) {
      observations.push(`json preview: ${(await preview.innerText()).replace(/\s+/g, ' ').slice(0, 260)}`)
    }
    // Every condition row must offer a non-empty, type-appropriate operator list.
    let emptyDropdowns = 0
    const perRow = []
    for (let i = 0; i < (await operators.count()); i++) {
      const count = await operators.nth(i).locator('option').count()
      perRow.push(`${await fields.nth(i).inputValue()}=${count}`)
      if (count === 0) emptyDropdowns++
    }
    observations.push(`operator options per row: ${perRow.join(', ')}`)
    observations.push(`rows with an EMPTY operator dropdown: ${emptyDropdowns}`)
    await p.keyboard.press('Escape')
    await p.waitForTimeout(800)
    return observations
  })

  await visit('admin-knowledge', '/admin/knowledge', async (p) => [
    `document rows: ${await p.locator('table tbody tr').count()}`,
    `table: ${(await p.locator('main').innerText()).replace(/\s+/g, ' ').slice(0, 320)}`,
  ])

  await visit('admin-users', '/admin/users', async (p) => [
    `user rows: ${await p.locator('table tbody tr').count()}`,
    `table: ${(await p.locator('main').innerText()).replace(/\s+/g, ' ').slice(0, 260)}`,
  ])

  await browser.close()

  const failed = results.filter((r) => r.pageErrors.length > 0)
  const consoleNoisy = results.filter((r) => r.consoleErrors.length > 0)
  console.log('\n==== SUMMARY ====')
  console.log(`routes visited      : ${results.length}`)
  console.log(`routes w/ pageerror : ${failed.length} ${failed.map((r) => r.name).join(', ')}`)
  console.log(`routes w/ console   : ${consoleNoisy.length} ${consoleNoisy.map((r) => r.name).join(', ')}`)
  console.log(`screenshots         : ${SHOTS}`)
  process.exit(failed.length === 0 ? 0 : 1)
}

main().catch((error) => {
  console.error('harness failed:', error)
  process.exit(3)
})
