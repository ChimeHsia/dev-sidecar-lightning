const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');
const { test } = require('node:test');

const directory = path.join(__dirname, 'fixtures/wave2');
const helpers = process.env.DSHA_KOTLIN_HELPERS
    ? require(path.resolve(process.env.DSHA_KOTLIN_HELPERS))
    : null;
const kotlinOptions = { skip: !helpers && 'Run scripts/test-migration-wave2.ps1 to compile and supply Kotlin/JS.' };
const upstream = process.env.DSHA_WAVE2_UPSTREAM
    ? path.resolve(process.env.DSHA_WAVE2_UPSTREAM, 'core/node_modules/@blue-frontier')
    : null;
const oracleOptions = { skip: !upstream && 'Set DSHA_WAVE2_UPSTREAM to replay fixtures against the original extracted core.' };
const clone = value => value == null ? null : JSON.parse(JSON.stringify(value));
const fixtures = name => JSON.parse(fs.readFileSync(path.join(directory, `${name}.json`), 'utf8'));

// Load only pure upstream helpers. Logger/agent stand-ins prevent importing the
// desktop runtime or opening sockets. Matching/merge/URL logic is the real source.
const logger = Object.fromEntries(['info', 'warn', 'debug', 'error'].map(name => [name, () => {}]));
const loaded = new Map();
function loadOriginal(relative) {
    const file = path.join(upstream, relative);
    if (loaded.has(file)) return loaded.get(file);
    const module = { exports: {} };
    const nativeRequire = createRequire(file);
    const localRequire = id => {
        if (id.includes('util.log')) return logger;
        if (id === './ProxyHttpAgent' || id === './ProxyHttpsAgent') {
            return class Agent { constructor(options) { this.options = options; } };
        }
        if (id === '../../../utils/util.match') return originalMatcher();
        return nativeRequire(id);
    };
    vm.runInThisContext(`(function(require, module, exports) {\n${fs.readFileSync(file, 'utf8')}\n})`, {
        filename: file,
    })(localRequire, module, module.exports);
    loaded.set(file, module.exports);
    return module.exports;
}
const originalMatcher = () => loadOriginal('mitmproxy/src/utils/util.match.js');

for (const item of fixtures('hostname-all')) {
    test(`Kotlin hostname-all: ${item.name}`, kotlinOptions, () => {
        const actual = JSON.parse(helpers.wave2HostnameAll(JSON.stringify(item.entries), item.hostname));
        assert.deepEqual(actual.values, item.expected);
        assert.deepEqual(Object.keys(actual.values || {}), item.expectedKeys || []);
        assert.deepEqual(actual.captureMetadata?.groups ?? null, item.expectedGroups ?? null);
        assert.equal(actual.captureMetadata?.index ?? null, item.expectedCaptureIndex ?? null);
        assert.equal(actual.captureMetadata?.input ?? null,
            Object.hasOwn(item, 'expectedCaptureIndex') ? item.hostname : null);
    });
    test(`Node oracle hostname-all: ${item.name}`, oracleOptions, () => {
        const matcher = originalMatcher();
        const actual = matcher.matchHostnameAll(matcher.domainMapRegexply(clone(item.entries)), item.hostname);
        assert.deepEqual(clone(actual), item.expected);
        assert.deepEqual(Object.keys(actual || {}), item.expectedKeys || []);
        assert.deepEqual(clone(actual?.matched?.groups), item.expectedGroups ?? null);
        assert.equal(actual?.matched?.index ?? null, item.expectedCaptureIndex ?? null);
    });
}

function upstreamExclusion(pathValue, exclusions) {
    const patterns = Array.isArray(exclusions)
        ? exclusions
        : (exclusions && typeof exclusions === 'object' ? Object.keys(exclusions) : []);
    return patterns.some(pattern => originalMatcher().isMatched(pathValue, pattern));
}

for (const item of fixtures('path-rules')) {
    test(`Kotlin path-rules: ${item.name}`, kotlinOptions, () => {
        const actual = JSON.parse(helpers.wave2PathMatch(JSON.stringify(item)));
        assert.deepEqual(actual.matched, item.expected);
        assert.equal(actual.excluded, item.expectedExcluded ?? false);
    });
    test(`Node oracle path-rules: ${item.name}`, oracleOptions, () => {
        assert.deepEqual(clone(originalMatcher().isMatched(item.path, item.pattern)), item.expected);
        if (Object.hasOwn(item, 'expectedExcluded')) {
            assert.equal(upstreamExclusion(item.path, item.exclusions), item.expectedExcluded);
        }
    });
}

function choiceOracle(item) {
    const { DynamicChoice } = loadOriginal('mitmproxy/src/lib/choice/index.js');
    const choice = new DynamicChoice('offline-wave-two');
    if (item.candidates.length) choice.setBackupList(item.candidates);
    const resetResults = [];
    for (const operation of item.operations) {
        const result = choice[operation.method](...operation.args);
        if (operation.method === 'resetChoice') resetResults.push(result);
    }
    return {
        value: choice.value ?? null,
        backupList: clone(choice.backupList),
        counts: Object.fromEntries(Object.entries(choice.countMap).map(([candidate, value]) => [candidate, {
            total: value.total, error: value.error,
            keepErrorCount: value.keepErrorCount, successRate: value.successRate,
        }])),
        resetResults,
    };
}

for (const item of fixtures('dynamic-choice')) {
    test(`Kotlin dynamic-choice: ${item.name}`, kotlinOptions, () => {
        const actual = JSON.parse(helpers.wave2ChoiceTrace(JSON.stringify(item)));
        assert.deepEqual(actual, item.expected);
        assert.deepEqual(Object.keys(actual.counts), Object.keys(item.expected.counts));
    });
    test(`Node oracle dynamic-choice: ${item.name}`, oracleOptions, () => {
        assert.deepEqual(choiceOracle(item), item.expected);
    });
}

for (const item of fixtures('request-transform')) {
    test(`Kotlin request-transform: ${item.name}`, kotlinOptions, () => {
        assert.deepEqual(JSON.parse(helpers.wave2RequestTransform(JSON.stringify(item.input))), item.expected);
    });
    test(`Node oracle request-transform: ${item.name}`, oracleOptions, () => {
        const util = loadOriginal('mitmproxy/src/lib/proxy/common/util.js');
        const actual = util.getOptionsFromRequest({ ...clone(item.input), socket: {} }, item.input.ssl, null, {
            timeoutMapping: originalMatcher().domainMapRegexply({}),
            tlsVersionMapping: {}, verifySsl: true,
        });
        const { protocol, hostname, port, path: requestPath, headers } = actual;
        assert.deepEqual(clone({ protocol, hostname, port, path: requestPath, headers }), item.expected);
    });
}

test('wave-two exports are explicit and do not replace the Node installation adapter', kotlinOptions, () => {
    for (const name of ['wave2HostnameAll', 'wave2PathMatch', 'wave2PathSelect', 'wave2ChoiceTrace', 'wave2RequestTransform']) {
        assert.equal(typeof helpers[name], 'function', name);
    }
    const adapter = fs.readFileSync(path.join(__dirname, '../node/kotlin-helpers.js'), 'utf8');
    assert.doesNotMatch(adapter, /wave2(?:HostnameAll|Path|Choice|Request)/);
});

test('path selections respect order, ties, stable priority and exclusions in Kotlin/JS', kotlinOptions, () => {
    const input = {
        path: '/api?q=1',
        rules: {
            '.*': { rewrite: true, audit: true, order: 1 },
            '^/api': { rewrite: true, order: 2 },
            'q=1': { rewrite: true, order: 2 },
            '^/': { audit: true, order: 99, exclusions: { 'q=1': false } },
            matched: { rewrite: true, order: 100 },
        },
        implementations: [
            { name: 'rewrite', priority: 20, enabledConfigKeys: ['rewrite'] },
            { name: 'audit', priority: 10, enabledConfigKeys: ['audit'] },
        ],
    };
    assert.deepEqual(JSON.parse(helpers.wave2PathSelect(JSON.stringify(input))), [
        { name: 'audit', priority: 10, key: '.*', order: 1, matched: ['/api?q=1'] },
        { name: 'rewrite', priority: 20, key: '^/api', order: 2, matched: ['/api'] },
    ]);
});

test('request validation retains the explicit rejection boundary on Kotlin/JS', kotlinOptions, () => {
    const valid = { url: '/', ssl: true, method: 'GET', headers: { host: 'example.test' } };
    for (const input of [
        { ...valid, url: 'https://user@host.test/' },
        { ...valid, url: '/bad%escape' },
        { ...valid, url: '//host.test/path' },
        { ...valid, headers: { host: 'example.test:65536' } },
        { ...valid, headers: { host: 'example.test', 'x-test': 'value\r\nInjected: true' } },
    ]) {
        assert.throws(() => helpers.wave2RequestTransform(JSON.stringify(input)));
    }
});
