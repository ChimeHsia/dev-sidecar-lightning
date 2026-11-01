'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { performance } = require('node:perf_hooks');
const { test, after } = require('node:test');
const { install } = require('../node/kotlin-choice-runtime');

// DSHA_WAVE2_UPSTREAM is the extracted project containing core/, not core itself.
// Integration uses the real compiled creator, original ChoiceCache singleton,
// original DNS subclass, and original proxy interceptor. Only logging and the
// resolver's external query are replaced; no JS selector stands in for Kotlin.
const helpers = process.env.DSHA_KOTLIN_HELPERS
    ? require(path.resolve(process.env.DSHA_KOTLIN_HELPERS))
    : null;
const coreRoot = process.env.DSHA_WAVE2_UPSTREAM
    ? path.resolve(process.env.DSHA_WAVE2_UPSTREAM, 'core')
    : null;
const integration = {
    skip: (!helpers || !coreRoot) &&
        'Set DSHA_KOTLIN_HELPERS and DSHA_WAVE2_UPSTREAM to run real Kotlin/upstream integration.',
};
const upstreamOnly = { skip: !coreRoot && 'Set DSHA_WAVE2_UPSTREAM to the project containing core/.' };
const logger = Object.fromEntries(['info', 'warn', 'debug', 'error'].map(key => [key, () => {}]));
const calls = [];
const creations = [];
let invocationCount = 0;
let choiceApi;
let BaseDNS;
let RequestCounter;
let proxyInterceptor;
let originalGetOrCreate;
let originalConstructor;
let historicalChoice;

if (coreRoot) {
    const source = path.join(coreRoot, 'node_modules/@blue-frontier/mitmproxy/src');
    const logFile = require.resolve(path.join(source, 'utils/util.log.server.js'));
    const oldLogger = require.cache[logFile];
    require.cache[logFile] = { id: logFile, filename: logFile, loaded: true, exports: logger };
    after(() => {
        if (oldLogger) require.cache[logFile] = oldLogger;
        else delete require.cache[logFile];
    });
    choiceApi = require(path.join(source, 'lib/choice/index.js'));
    originalConstructor = choiceApi.DynamicChoice;
    originalGetOrCreate = choiceApi.ChoiceCache.prototype.getOrCreate;
    historicalChoice = new originalConstructor('initialized-before-install');
    historicalChoice.setBackupList(['historical', 'fallback']);
    // Capture both lexical constructors BEFORE installation. An export-only
    // replacement cannot make either of these callers reach Kotlin.
    BaseDNS = require(path.join(source, 'lib/dns/base.js'));
    RequestCounter = require(path.join(source, 'lib/choice/RequestCounter.js'));
    proxyInterceptor = require(path.join(source, 'lib/interceptor/impl/req/proxy.js'));
}

const observedHelpers = {
    createDynamicChoice(key) {
        const real = helpers.createDynamicChoice(key);
        creations.push({ key, real });
        return new Proxy(real, {
            get(target, method) {
                const value = Reflect.get(target, method, target);
                if (typeof value !== 'function') return value;
                return (...args) => {
                    calls.push({ key, method, args });
                    return Reflect.apply(value, target, args);
                };
            },
        });
    },
};

function runtime() {
    assert.equal(typeof helpers.createDynamicChoice, 'function',
        'Rebuild Kotlin/JS including ChoiceRuntimeExports.kt; trace-only exports cannot serve production.');
    return install(coreRoot, observedHelpers, { onUse: () => { invocationCount++; } });
}

function snapshot(choice) {
    return {
        value: choice.value,
        backupList: [...choice.backupList],
        counts: Object.fromEntries(Object.entries(choice.countMap).map(([candidate, count]) => {
            assert.equal(count.value, candidate);
            assert.deepEqual(Object.keys(count), ['value', 'total', 'error', 'keepErrorCount', 'successRate']);
            const { total, error, keepErrorCount, successRate } = count;
            return [candidate, { total, error, keepErrorCount, successRate }];
        })),
    };
}

function dnsWithAnswers(answers, cacheSize = 2) {
    const dns = new BaseDNS(null, 4, 'offline', 'test', cacheSize, null);
    dns.queries = 0;
    dns._lookupWithPreSetIpList = async () => {
        dns.queries++;
        return [...answers];
    };
    return dns;
}

test('missing stateful Kotlin export fails before loading any upstream code', () => {
    for (const value of [null, {}, { wave2ChoiceTrace() {} }, { createDynamicChoice: true }]) {
        assert.throws(() => install('/does-not-exist', value), /Missing Kotlin helper: createDynamicChoice/);
    }
    assert.throws(() => install('/does-not-exist', observedHelpers, { onUse: true }), /onUse/);
});

test('upstream cache policies and captured constructors exist without starting the core', upstreamOnly, () => {
    const cache = new choiceApi.ChoiceCache();
    assert.equal(cache.cache.maxSize, 1024);
    assert.equal(cache.cache.ttl, 0);
    assert.equal(RequestCounter.cache.maxSize, 1024);
    assert.equal(new BaseDNS().cache.maxSize, 1024);
    assert.equal(new BaseDNS(null, 4, 'offline', 'test', 2).cache.maxSize, 2);
});

test('original getOrCreate reaches the stateful Kotlin creator and emits one actual-use callback', integration, () => {
    assert.equal(runtime(), choiceApi);
    assert.equal(choiceApi.DynamicChoice, originalConstructor);
    assert.equal(choiceApi.ChoiceCache.prototype.getOrCreate, originalGetOrCreate);
    assert.equal(invocationCount, 0, 'installation alone is not invocation evidence');
    const cache = new choiceApi.ChoiceCache();
    const choice = new choiceApi.DynamicChoice('invalid-before-first-use');
    assert.throws(() => choice.setBackupList(['valid', '']), /Candidate/);
    assert.equal(invocationCount, 0, 'a rejected Kotlin operation must not report successful use');
    assert.deepEqual(snapshot(choice), { value: null, backupList: [], counts: {} });

    const before = creations.length;
    const timeBefore = Date.now();
    const item = cache.getOrCreate('example.test/rule', ['192.0.2.1', '192.0.2.2', 'example.test']);
    const timeAfter = Date.now();
    assert.equal(creations.length, before + 1);
    assert.equal(creations.at(-1).key, 'example.test/rule');
    assert(creations.at(-1).real instanceof helpers.KotlinDynamicChoiceRuntime);
    assert.equal(typeof creations.at(-1).real.doCount, 'function');
    assert(calls.some(call => call.key === item.key && call.method === 'setBackupList'));
    assert.equal(item.key, 'example.test/rule');
    assert(item instanceof originalConstructor);
    assert(item.createTime instanceof Date);
    assert(item.createTime.getTime() >= timeBefore && item.createTime.getTime() <= timeAfter);
    assert.equal(Object.getPrototypeOf(item.countMap), Object.prototype);
    assert.deepEqual(snapshot(item), {
        value: '192.0.2.1',
        backupList: ['192.0.2.2', 'example.test'],
        counts: {
            '192.0.2.1': { total: 4, error: 0, keepErrorCount: 0, successRate: 1 },
            '192.0.2.2': { total: 2, error: 0, keepErrorCount: 0, successRate: 0.5 },
            'example.test': { total: 1, error: 0, keepErrorCount: 0, successRate: 0.5 },
        },
    });
    const createdAt = item.createTime;
    const map = item.countMap;
    const count = map['192.0.2.1'];
    const backups = item.backupList;
    assert.equal(item.countStart(item.value), undefined);
    assert.equal(count.total, 5);
    assert.equal(item.doCount(item.value, true), undefined);
    assert.equal(item.value, '192.0.2.2');
    assert.equal(item.countMap, map);
    assert.equal(item.countMap['192.0.2.1'], count);
    assert.equal(item.backupList, backups);
    assert.equal(item.createTime, createdAt);
    assert.equal(cache.getOrCreate(item.key, ['replacement']), item);
    assert.equal(cache.get(item.key), item);
    assert.equal(creations.length, before + 1, 'cache hits must retain Kotlin feedback');
    assert.equal(invocationCount, 1);
    assert.equal(runtime(), choiceApi);
    item.doRank();
    assert.equal(invocationCount, 1, 'repeat installation must not reset the first-use hook');
    assert.throws(() => install(coreRoot, { createDynamicChoice() {} }), /already installed/);
});

test('no JS fallback when the Kotlin creator or a Kotlin mutation throws', integration, () => {
    runtime();
    const creator = observedHelpers.createDynamicChoice;
    const factoryFailure = new Error('Kotlin creator failure');
    observedHelpers.createDynamicChoice = () => { throw factoryFailure; };
    const cache = new choiceApi.ChoiceCache();
    try {
        assert.throws(() => cache.getOrCreate('factory-failure', ['first']), error => error === factoryFailure);
        assert.equal(cache.get('factory-failure'), undefined);
    } finally {
        observedHelpers.createDynamicChoice = creator;
    }
    const item = new choiceApi.ChoiceCache().getOrCreate('throws', ['first', 'last']);
    const before = snapshot(item);
    const real = creations.at(-1).real;
    const method = real.doCount;
    const failure = new Error('Kotlin mutation failure');
    real.doCount = () => { throw failure; };
    try {
        assert.throws(() => item.doCount('first', true), error => error === failure);
        assert.deepEqual(snapshot(item), before);
    } finally {
        real.doCount = method;
    }
});

test('late installation fails explicitly instead of discarding historical JS feedback', integration, () => {
    runtime();
    const before = snapshot(historicalChoice);
    assert.throws(() => historicalChoice.doCount('historical', true), /before initializing choices/);
    assert.deepEqual(snapshot(historicalChoice), before);
});

test('upstream LRU eviction and explicit TTL expiry create fresh independent Kotlin state', integration, () => {
    runtime();
    const cache = new choiceApi.ChoiceCache();
    const first = cache.getOrCreate('oldest', ['first', 'fallback']);
    first.doCount('first', true);
    first.createTime = new Date(0);
    assert.equal(cache.getOrCreate('oldest', ['ignored']), first,
        'createTime is diagnostic, not a selector expiry policy');
    for (let i = 0; i < 1024; i++) cache.getOrCreate(`key-${i}`, ['first']);
    assert.equal(cache.cache.size, 1024);
    assert.equal(cache.get('oldest'), undefined);
    const replacement = cache.getOrCreate('oldest', ['new']);
    assert.notEqual(replacement, first);
    assert.equal(replacement.value, 'new');
    assert.notEqual(replacement.createTime, first.createTime);

    const expiring = cache.getOrCreate('expires', ['expired']);
    cache.cache.set('expires', expiring, { ttl: 1, start: performance.now() - 1000 });
    assert.equal(cache.get('expires'), undefined);
    const fresh = cache.getOrCreate('expires', ['fresh']);
    assert.notEqual(fresh, expiring);
    assert.equal(fresh.value, 'fresh');
    assert.equal(cache.cache.maxSize, 1024);
    assert.equal(cache.cache.ttl, 0);
});

test('original proxy interceptor uses the original RequestCounter singleton and Kotlin feedback', integration, () => {
    runtime();
    const context = {
        rOptions: { hostname: 'origin.test', protocol: 'https:', path: '/path?q=1', headers: {} },
        log: logger,
        RequestCounter,
    };
    const rule = { key: '^/path', proxy: 'first.test', backup: ['second.test'] };
    const before = calls.length;
    const headers = {};
    assert.equal(proxyInterceptor.requestIntercept(context, rule, {}, {
        setHeader(key, value) { headers[key] = value; },
    }), true);
    assert.equal(context.requestCount.key, 'origin.test/^/path');
    const item = RequestCounter.get(context.requestCount.key);
    assert.equal(context.requestCount.count, item);
    assert.equal(context.rOptions.hostname, 'first.test');
    assert(calls.slice(before).some(call => call.key === item.key && call.method === 'setBackupList'));
    assert(calls.slice(before).some(call => call.key === item.key && call.method === 'doCount' && call.args[1] === false));
    item.doCount(context.requestCount.value, true);
    assert.equal(item.value, 'second.test');
    item.doCount('second.test', true);
    assert.equal(item.value, null);
    context.rOptions = { hostname: 'origin.test', protocol: 'https:', path: '/path?q=1', headers: {} };
    proxyInterceptor.requestIntercept(context, rule, {}, { setHeader() {} });
    assert(calls.some(call => call.key === item.key && call.method === 'doRank'));
    assert.equal(context.requestCount.count, item);
    assert.equal(context.rOptions.hostname, 'first.test');
});

const fixtures = JSON.parse(fs.readFileSync(path.join(__dirname, 'fixtures/wave2/dynamic-choice.json'), 'utf8'));
for (const fixture of fixtures) {
    test(`real ChoiceCache/Kotlin transitions: ${fixture.name}`, integration, () => {
        runtime();
        const choice = new choiceApi.ChoiceCache().getOrCreate(`fixture/${fixture.name}`, fixture.candidates);
        const resetResults = [];
        for (const operation of fixture.operations) {
            const result = choice[operation.method](...operation.args);
            if (operation.method === 'resetChoice') resetResults.push(result);
        }
        const actual = { ...snapshot(choice), resetResults };
        assert.deepEqual(actual, fixture.expected);
        assert.deepEqual(Object.keys(actual.counts), Object.keys(fixture.expected.counts));
    });
}

test('empty lists and empty ranks preserve the documented Kotlin boundary', integration, () => {
    runtime();
    const cache = new choiceApi.ChoiceCache();
    const empty = cache.getOrCreate('empty', []);
    empty.doRank();
    assert.deepEqual(snapshot(empty), { value: null, backupList: [], counts: {} });
    empty.setBackupList(['undefined']);
    assert.equal(empty.countMap.undefined.value, 'undefined');
    empty.setBackupList([]);
    assert.equal(empty.value, null);
    assert.deepEqual(empty.backupList, []);
    assert.equal(empty.countMap.undefined.total, 2, 'empty replacement adds no synthetic count');
    empty.doRank();
    assert.equal(empty.value, 'undefined');
    assert.equal(empty.countMap.undefined.total, 3);
    for (const candidate of ['', '__proto__', 'constructor', 'toString']) {
        const before = snapshot(empty);
        assert.throws(() => empty.setBackupList(['valid', candidate]), /Candidate/);
        assert.throws(() => empty.countStart(candidate), /Candidate/);
        assert.throws(() => empty.doCount(candidate, true), /Candidate/);
        assert.throws(() => empty.resetChoice(candidate), /Candidate/);
        assert.deepEqual(snapshot(empty), before);
    }
});

test('already-loaded DNS subclass uses Kotlin for feedback, late failures and hostname reset', integration, async () => {
    runtime();
    const dns = dnsWithAnswers(['192.0.2.1', '192.0.2.2']);
    const hostname = 'dns-feedback.test';
    const before = calls.length;
    assert.equal(await dns.lookup(hostname), '192.0.2.1');
    const item = dns.cache.get(hostname);
    const createdAt = item.createTime;
    assert(item instanceof originalConstructor);
    assert.equal(item.lookupCount, 1);
    assert.deepEqual(item.backupList, ['192.0.2.2', hostname]);
    assert.equal(await dns.lookup(hostname), '192.0.2.1');
    assert.equal(dns.queries, 1);
    dns.count(hostname, '192.0.2.1'); // Default isError=true in the real BaseDNS.
    assert.equal(await dns.lookup(hostname), '192.0.2.2');
    dns.count(hostname, '192.0.2.1', true); // Late feedback must not switch current.
    assert.equal(item.value, '192.0.2.2');
    dns.count(hostname, '192.0.2.2', true);
    assert.equal(await dns.lookup(hostname), hostname);
    assert.equal(await dns.lookup(hostname, { resetOnHostnameFallback: true }), '192.0.2.1');
    assert.equal(dns.cache.get(hostname), item);
    assert.equal(item.lookupCount, 1);
    assert.equal(item.createTime, createdAt);
    assert.equal(dns.queries, 1);
    assert.equal(item.countMap['192.0.2.1'].total, 1);
    assert.equal(item.countMap[hostname].total, 0);
    assert.equal(await dns.lookup(hostname, { ipChecker: () => true }), '192.0.2.1');
    assert.equal(item.countMap['192.0.2.1'].total, 2);
    assert(calls.slice(before).some(call => call.key === hostname && call.method === 'doCount'));
    assert(calls.slice(before).some(call => call.key === hostname && call.method === 'resetChoice'));
});

test('DNS exhaustion refreshes the existing selector without altering its timestamp or cache clock', integration, async () => {
    runtime();
    const dns = dnsWithAnswers(['192.0.2.1']);
    const hostname = 'dns-exhaustion.test';
    await dns.lookup(hostname);
    const item = dns.cache.get(hostname);
    const createdAt = item.createTime;
    dns.count(hostname, '192.0.2.1', true);
    dns.count(hostname, hostname, true);
    assert.equal(item.value, null);
    assert.equal(await dns.lookup(hostname), '192.0.2.1');
    assert.equal(item.lookupCount, 2);
    assert.equal(item.createTime, createdAt);
    assert.equal(dns.cache.get(hostname), item);
    assert.equal(dns.queries, 2);
    assert.equal(dns.cache.ttl, 0);

    dns.cache.set(hostname, item, { ttl: 1, start: performance.now() - 1000 });
    assert.equal(await dns.lookup(hostname), '192.0.2.1');
    const fresh = dns.cache.get(hostname);
    assert.notEqual(fresh, item);
    assert.equal(fresh.lookupCount, 1);
    assert.equal(dns.queries, 3);
});

test('DNS hostname-only reset returns false and upstream replaces its cache entry', integration, async () => {
    runtime();
    const dns = dnsWithAnswers([]);
    const hostname = 'hostname-only.test';
    assert.equal(await dns.lookup(hostname), hostname);
    const first = dns.cache.get(hostname);
    assert.equal(first.resetChoice(hostname), false);
    assert.equal(first.value, hostname);
    assert.deepEqual(first.backupList, []);
    assert.equal(await dns.lookup(hostname, { resetOnHostnameFallback: true }), hostname);
    assert.notEqual(dns.cache.get(hostname), first);
    assert.equal(dns.queries, 2);
});

test('DNS retains its configured LRU limit', integration, async () => {
    runtime();
    const dns = dnsWithAnswers(['192.0.2.1'], 2);
    await dns.lookup('one.test');
    await dns.lookup('two.test');
    await dns.lookup('one.test');
    await dns.lookup('three.test');
    assert.equal(dns.cache.maxSize, 2);
    assert.equal(dns.cache.size, 2);
    assert.equal(dns.cache.has('one.test'), true);
    assert.equal(dns.cache.has('two.test'), false);
    assert.equal(dns.cache.has('three.test'), true);
});
