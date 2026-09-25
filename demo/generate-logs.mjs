#!/usr/bin/env node
/**
 * Generates the Nightshift demo dataset: seven days of Spring Boot style logs for
 * three services, with eight defects seeded into them.
 *
 *   node demo/generate-logs.mjs [--days 7] [--lines-per-day 900] [--end 2026-09-24]
 *
 * Two properties matter more than the volume:
 *
 *  1. Every stack frame line number is read out of demo/target-repo at generation
 *     time by locating an `NS_FRAME*` marker comment. Edit the fixture and
 *     re-run, and the logs follow. Nothing to keep in sync by hand.
 *
 *  2. The output is deterministic. A fixed seed means a regenerated dataset is
 *     byte-identical, so the evaluation harness scores against a stable corpus and
 *     a diff on demo/logs/ is always a real change.
 *
 * Compliance: 100% synthetic. No client data, no company data, no personal data,
 * nothing scraped. Names are placeholders, IPs come from the RFC 5737 TEST-NET
 * ranges, emails use example.com. See docs/DATA_PROVENANCE.md.
 */

import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = join(HERE, 'target-repo');
const OUT = join(HERE, 'logs');

// ── args ─────────────────────────────────────────────────────────────────────

const args = process.argv.slice(2);
const arg = (name, fallback) => {
  const i = args.indexOf(`--${name}`);
  return i >= 0 && args[i + 1] ? args[i + 1] : fallback;
};

const DAYS = Number(arg('days', 7));
const LINES_PER_DAY = Number(arg('lines-per-day', 900));
const END_DATE = arg('end', '2026-09-24'); // last full day in the window

// ── deterministic PRNG (mulberry32) ──────────────────────────────────────────

let seed = 0x9e3779b9;
function rnd() {
  seed |= 0;
  seed = (seed + 0x6d2b79f5) | 0;
  let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
  t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
  return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
}
const pick = (xs) => xs[Math.floor(rnd() * xs.length)];
const between = (lo, hi) => lo + Math.floor(rnd() * (hi - lo + 1));
const hex = (n) => Array.from({ length: n }, () => '0123456789abcdef'[Math.floor(rnd() * 16)]).join('');

// ── resolve real line numbers from the fixture repo ──────────────────────────

const SOURCES = {
  NameFormatter: 'src/main/java/com/example/common/NameFormatter.java',
  FarmerSyncService: 'src/main/java/com/example/farmer/FarmerSyncService.java',
  FarmerProfileService: 'src/main/java/com/example/farmer/FarmerProfileService.java',
  PaymentRetryClient: 'src/main/java/com/example/payment/PaymentRetryClient.java',
  PaymentProfileEnricher: 'src/main/java/com/example/payment/PaymentProfileEnricher.java',
  InventoryReportService: 'src/main/java/com/example/sync/InventoryReportService.java',
  ShipmentEventConsumer: 'src/main/java/com/example/sync/ShipmentEventConsumer.java',
};

/** 1-based line number of the unique line carrying `marker` in that fixture. */
function frameLine(className, marker) {
  const path = join(REPO, SOURCES[className]);
  const lines = readFileSync(path, 'utf8').split(/\r?\n/);
  const idx = lines.findIndex((l) => l.includes(marker));
  if (idx < 0) {
    throw new Error(`marker ${marker} not found in ${SOURCES[className]} — fixture and generator are out of sync`);
  }
  return idx + 1;
}

const L = {
  npeThrow: frameLine('NameFormatter', 'NS_FRAME'),
  npeCallerFarmer: frameLine('FarmerSyncService', 'NS_FRAME_NPE'),
  npeCallerPayment: frameLine('PaymentProfileEnricher', 'NS_FRAME_NPE2'),
  pool: frameLine('FarmerSyncService', 'NS_FRAME_POOL'),
  retry: frameLine('PaymentRetryClient', 'NS_FRAME_RETRY'),
  nPlusOne: frameLine('InventoryReportService', 'NS_FRAME_NPLUSONE'),
  deser: frameLine('ShipmentEventConsumer', 'NS_FRAME_DESER'),
  optLock: frameLine('FarmerProfileService', 'NS_FRAME_OPTLOCK'),
};

// ── log line formatting ──────────────────────────────────────────────────────
//
// Pattern the parser must handle:
//   %d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [service,traceId,spanId] [thread] logger - msg

const SERVICES = [
  { name: 'farmer-service', pkg: 'com.example.farmer', threads: ['http-nio-8080-exec-', 'farmer-sync-'] },
  { name: 'payment-service', pkg: 'com.example.payment', threads: ['http-nio-8081-exec-', 'settlement-'] },
  { name: 'sync-service', pkg: 'com.example.sync', threads: ['http-nio-8082-exec-', 'report-builder-'] },
];

function stamp(date, ms) {
  const d = new Date(date.getTime() + ms);
  const p = (n, w = 2) => String(n).padStart(w, '0');
  return `${d.getUTCFullYear()}-${p(d.getUTCMonth() + 1)}-${p(d.getUTCDate())} ` +
    `${p(d.getUTCHours())}:${p(d.getUTCMinutes())}:${p(d.getUTCSeconds())}.${p(d.getUTCMilliseconds(), 3)}`;
}

function line(service, date, ms, level, logger, msg, thread) {
  return `${stamp(date, ms)} ${level.padEnd(5)} [${service.name},${hex(16)},${hex(8)}] ` +
    `[${thread ?? pick(service.threads) + between(1, 9)}] ${logger} - ${msg}`;
}

const framesFor = (kind) => {
  switch (kind) {
    case 'npe-farmer':
      return [
        `java.lang.NullPointerException: Cannot invoke "String.charAt(int)" because "middleName" is null`,
        `\tat com.example.common.NameFormatter.initials(NameFormatter.java:${L.npeThrow})`,
        `\tat com.example.farmer.FarmerSyncService.cardLabel(FarmerSyncService.java:${L.npeCallerFarmer})`,
        `\tat com.example.farmer.CardRenderJob.render(CardRenderJob.java:74)`,
        `\tat java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1144)`,
        `\tat java.base/java.lang.Thread.run(Thread.java:1583)`,
      ];
    case 'npe-payment':
      return [
        `java.lang.NullPointerException: Cannot invoke "String.charAt(int)" because "middleName" is null`,
        `\tat com.example.common.NameFormatter.initials(NameFormatter.java:${L.npeThrow})`,
        `\tat com.example.payment.PaymentProfileEnricher.payeeInitials(PaymentProfileEnricher.java:${L.npeCallerPayment})`,
        `\tat com.example.payment.PayoutController.preview(PayoutController.java:112)`,
        `\tat java.base/java.lang.Thread.run(Thread.java:1583)`,
      ];
    case 'pool':
      return [
        `java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available, request timed out after 30001ms (total=10, active=10, idle=0, waiting=7)`,
        `\tat com.zaxxer.hikari.pool.HikariPool.createTimeoutException(HikariPool.java:696)`,
        `\tat com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:181)`,
        `\tat com.example.farmer.FarmerSyncService.pushPending(FarmerSyncService.java:${L.pool})`,
        `\tat com.example.farmer.FarmerSyncScheduler.run(FarmerSyncScheduler.java:58)`,
      ];
    case 'deser':
      return [
        `com.example.sync.ShipmentEventConsumer$DeserializationException: Unexpected token START_ARRAY at [Source: (byte[]); line: 1, column: 14]`,
        `\tat com.example.sync.ShipmentEventConsumer.onMessage(ShipmentEventConsumer.java:${L.deser})`,
        `\tat org.springframework.kafka.listener.KafkaMessageListenerContainer.doInvokeRecordListener(KafkaMessageListenerContainer.java:2815)`,
        `Caused by: com.fasterxml.jackson.core.JsonParseException: Unexpected token START_ARRAY`,
        `\tat com.fasterxml.jackson.core.JsonParser._constructError(JsonParser.java:2477)`,
      ];
    case 'optlock':
      return [
        `com.example.farmer.FarmerProfileService$OptimisticLockException: Row was updated by another transaction (farmer id=${between(1000, 9999)})`,
        `\tat com.example.farmer.FarmerProfileService.rename(FarmerProfileService.java:${L.optLock})`,
        `\tat com.example.farmer.FarmerController.rename(FarmerController.java:96)`,
      ];
    default:
      return [];
  }
};

// ── benign traffic, so the defects have to be found rather than handed over ──

const NOISE = {
  'farmer-service': [
    ['INFO', 'com.example.farmer.FarmerController', () => `GET /api/v1/farmers?page=${between(0, 40)} 200 in ${between(11, 140)}ms`],
    ['INFO', 'com.example.farmer.FarmerSyncScheduler', () => `Sync batch ${between(8000, 9000)} queued, ${between(20, 400)} pending records`],
    ['INFO', 'com.example.farmer.CardRenderJob', () => `Rendered ${between(5, 90)} cards in ${between(200, 2400)}ms`],
    ['DEBUG', 'com.example.farmer.FarmerRepository', () => `select f.* from farmer f where f.region_id = ${between(1, 64)}`],
    ['INFO', 'com.example.common.AuditPublisher', () => `Published ${between(1, 30)} audit events to nightshift.audit`],
    ['WARN', 'com.example.farmer.FarmerController', () => `Request from 192.0.2.${between(1, 254)} missing X-Request-Id header, generated one`],
  ],
  'payment-service': [
    ['INFO', 'com.example.payment.PayoutController', () => `POST /api/v1/payouts 201 in ${between(40, 320)}ms`],
    ['INFO', 'com.example.payment.PaymentRetryClient', () => `Settled reference SET-${between(100000, 999999)} amount=${between(500, 90000)}`],
    ['DEBUG', 'com.example.payment.SettlementApiClient', () => `POST https://bank.example.com/v2/settle -> 200 in ${between(80, 900)}ms`],
    ['INFO', 'com.example.payment.ReconciliationJob', () => `Reconciled ${between(100, 1200)} rows, ${between(0, 3)} mismatches`],
    ['WARN', 'com.example.payment.SettlementApiClient', () => `Upstream latency ${between(1200, 2600)}ms exceeds the 1000ms budget`],
  ],
  'sync-service': [
    ['INFO', 'com.example.sync.InventoryReportService', () => `Daily report for region ${pick(['north', 'south', 'east', 'west'])} built, ${between(400, 1400)} dealers`],
    ['INFO', 'com.example.sync.ShipmentEventConsumer', () => `Consumed ${between(10, 300)} shipment events from partition ${between(0, 5)}`],
    ['DEBUG', 'org.springframework.kafka.listener.KafkaMessageListenerContainer', () => `partitions assigned: [shipment-events-${between(0, 5)}]`],
    ['INFO', 'com.example.sync.StockLevelService', () => `Stock refresh completed for ${between(50, 900)} SKUs`],
    ['WARN', 'org.apache.kafka.clients.consumer.internals.ConsumerCoordinator', () => `Offset commit failed on partition shipment-events-${between(0, 5)}, retrying`],
  ],
};

// ── the seeded defects ───────────────────────────────────────────────────────
//
// dayIndex is 0-based from the oldest day, so a count can carry a trend.

const DEFECTS = [
  {
    id: 1,
    service: 'farmer-service',
    level: 'ERROR',
    logger: 'com.example.farmer.FarmerSyncService',
    // Rising: 8 on the first day, ~28x that by day seven. The trend IS the signal —
    // a flat 400/day is a known annoyance, a rising 400 is an outage with a date.
    count: (d) => Math.round(8 * Math.pow(1.62, d)),
    message: () => `Sync failed for batch ${between(8000, 9000)}: could not acquire a database connection`,
    frames: 'pool',
  },
  {
    id: 2,
    service: 'farmer-service',
    level: 'ERROR',
    logger: 'com.example.farmer.CardRenderJob',
    count: () => between(6, 11),
    message: () => `Failed to render card label for farmer ${between(1000, 99999)}`,
    frames: 'npe-farmer',
  },
  {
    id: 3,
    service: 'sync-service',
    level: 'WARN',
    logger: 'com.example.sync.InventoryReportService',
    count: () => between(26, 34),
    // No exception, so no stack trace: the locator has to fall back to the logger
    // name, and a grep for ERROR never sees this at all.
    message: () => `Slow report build: region=${pick(['north', 'south', 'east', 'west'])} dealers=${between(900, 1300)} ` +
      `queries=${between(901, 1301)} duration=${between(9000, 26000)}ms (InventoryReportService.java:${L.nPlusOne})`,
    frames: null,
  },
  {
    id: 4,
    service: 'payment-service',
    level: 'WARN',
    logger: 'com.example.payment.PaymentRetryClient',
    // Bursty: two bad afternoons, hundreds of lines inside a few seconds.
    count: (d) => (d === 2 || d === 5 ? between(140, 170) : between(0, 4)),
    burst: true,
    message: () => `Settlement rate limited (HTTP 429, Retry-After=${between(1, 30)}s), retrying immediately ` +
      `[PaymentRetryClient.java:${L.retry}]`,
    frames: null,
  },
  {
    id: 5,
    service: 'sync-service',
    level: 'WARN',
    logger: 'com.example.sync.ShipmentEventConsumer',
    count: () => between(4, 8),
    message: () => `Dropping malformed shipment event from topic shipment-events, offset ${between(100000, 999999)}`,
    frames: 'deser',
  },
  {
    id: 6,
    service: 'farmer-service',
    level: 'WARN',
    logger: 'com.example.farmer.FarmerProfileService',
    count: () => between(12, 19),
    message: () => `Concurrent update on farmer ${between(1000, 9999)}, retry ${between(1, 2)} of 3`,
    frames: 'optlock',
  },
  {
    id: 7,
    // Three restarts a day, one deprecation warning each. Pure noise: the correct
    // outcome is TRIVIAL and no pull request.
    service: 'all',
    level: 'WARN',
    logger: 'org.springframework.boot.context.config.ConfigDataEnvironment',
    count: () => 3,
    message: () => `Property 'spring.datasource.initialization-mode' is deprecated and will be removed in a future release`,
    frames: null,
  },
  {
    id: 8,
    service: 'payment-service',
    level: 'ERROR',
    logger: 'com.example.payment.PayoutController',
    count: () => between(3, 6),
    message: () => `Payout preview failed for payee ${between(1000, 99999)}`,
    frames: 'npe-payment',
  },
];

// ── build the files ──────────────────────────────────────────────────────────

const DAY_MS = 86_400_000;
const endDay = new Date(`${END_DATE}T00:00:00.000Z`);
const days = Array.from({ length: DAYS }, (_, i) =>
  new Date(endDay.getTime() - (DAYS - 1 - i) * DAY_MS));

const iso = (d) => d.toISOString().slice(0, 10);

let totalLines = 0;
let totalDefectLines = 0;
const perDefect = new Map();

for (const service of SERVICES) {
  mkdirSync(join(OUT, service.name), { recursive: true });

  days.forEach((day, dayIndex) => {
    /** @type {{ms:number, text:string}[]} */
    const entries = [];

    // Business-hours-weighted benign traffic.
    for (let i = 0; i < LINES_PER_DAY; i++) {
      const hour = rnd() < 0.78 ? between(8, 19) : between(0, 23);
      const ms = hour * 3_600_000 + between(0, 59) * 60_000 + between(0, 59) * 1000 + between(0, 999);
      const [level, logger, msg] = pick(NOISE[service.name]);
      entries.push({ ms, text: line(service, day, ms, level, logger, msg()) });
    }

    // Seeded defects.
    for (const defect of DEFECTS) {
      if (defect.service !== 'all' && defect.service !== service.name) continue;

      const count = defect.count(dayIndex);
      const burstStart = defect.burst
        ? between(13, 16) * 3_600_000 + between(0, 59) * 60_000
        : null;

      for (let i = 0; i < count; i++) {
        const ms = burstStart !== null
          ? burstStart + i * between(40, 260)
          : between(0, 23) * 3_600_000 + between(0, 59) * 60_000 + between(0, 59) * 1000 + between(0, 999);

        const head = line(service, day, ms, defect.level, defect.logger, defect.message());
        const trace = defect.frames ? framesFor(defect.frames) : [];
        entries.push({ ms, text: [head, ...trace].join('\n') });

        totalDefectLines += 1 + trace.length;
        perDefect.set(defect.id, (perDefect.get(defect.id) ?? 0) + 1);
      }
    }

    entries.sort((a, b) => a.ms - b.ms);
    const body = entries.map((e) => e.text).join('\n') + '\n';
    totalLines += body.split('\n').length - 1;

    writeFileSync(join(OUT, service.name, `${service.name}.${iso(day)}.log`), body, 'utf8');
  });
}

// ── report ───────────────────────────────────────────────────────────────────

console.log(`Nightshift demo dataset`);
console.log(`  window        ${iso(days[0])} .. ${iso(days[DAYS - 1])} (${DAYS} days)`);
console.log(`  services      ${SERVICES.map((s) => s.name).join(', ')}`);
console.log(`  files         ${SERVICES.length * DAYS}`);
console.log(`  total lines   ${totalLines.toLocaleString('en-US')}`);
console.log(`  defect lines  ${totalDefectLines.toLocaleString('en-US')} (${((totalDefectLines / totalLines) * 100).toFixed(2)}% of the corpus)`);
console.log(`  frame lines resolved from target-repo: ${JSON.stringify(L)}`);
console.log(`  occurrences per seeded defect:`);
for (const [id, n] of [...perDefect.entries()].sort((a, b) => a[0] - b[0])) {
  console.log(`    #${id}  ${String(n).padStart(5)}`);
}
console.log(`\nA correct pipeline reports 7 distinct incidents (#2 and #8 fold into one)`);
console.log(`and opens 5 pull requests (#6 is already handled, #7 is noise).`);
