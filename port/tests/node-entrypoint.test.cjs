const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const entrypoint = path.resolve(__dirname, '../node/main.js');
const source = fs.readFileSync(entrypoint, 'utf8');
const androidPath = path.posix;
const root = '/android/app/files/nodejs-project';
const core = androidPath.join(root, 'core');
const home = androidPath.join(root, 'home');
const base = androidPath.join(home, '.dev-sidecar');
const configFile = androidPath.join(base, 'config.json');
const serverConfigFile = androidPath.join(base, 'server.json');
const coreModule = androidPath.join(core, 'node_modules/@blue-frontier/dev-sidecar');
const proxyModule = androidPath.join(core, 'node_modules/@blue-frontier/mitmproxy');
const kotlinModule = androidPath.join(root, 'kotlin/dev-sidecar-lightning-netcore-js.js');

function defaultServerConfig() {
    return {
        port: 1234,
        setting: { rootDir: '/desktop/core', retained: 'setting' },
        intercepts: {
            'disabled.example': null,
            'mixed.example': {
                '/disabled': null,
                '/enabled': { redirect: 'https://destination.example' },
                '/false': false,
                '/zero': 0,
                '/empty': '',
            },
            'empty.example': {},
        },
        retained: { value: 'server option' },
    };
}

async function boot(options = {}) {
    const files = new Map([
        [serverConfigFile, JSON.stringify(options.serverConfig ?? defaultServerConfig())],
    ]);
    if (Object.hasOwn(options, 'existingConfig')) {
        files.set(configFile, options.existingConfig);
    }

    const observed = {
        files,
        directories: [],
        writes: [],
        coreLoads: [],
        startupCalls: [],
        serverStarts: [],
        logs: [],
        errors: [],
        handlers: new Map(),
        systemProxyAttempts: [],
        hookResults: [],
        installations: [],
    };
    const fakeProcess = {
        pid: 4242,
        env: {
            HOME: '/desktop/home',
            USERPROFILE: '/desktop/profile',
            DS_EXTRA_PATH: '/desktop/extra',
            ...options.env,
        },
        on(event, handler) {
            const handlers = observed.handlers.get(event) ?? [];
            handlers.push(handler);
            observed.handlers.set(event, handlers);
            return this;
        },
    };
    const fakeFs = {
        mkdirSync(directory, settings) {
            observed.directories.push({ directory, recursive: settings?.recursive });
        },
        existsSync(file) {
            return files.has(file);
        },
        writeFileSync(file, contents) {
            observed.writes.push(file);
            files.set(file, String(contents));
        },
        readFileSync(file) {
            assert.ok(files.has(file), `Unexpected filesystem read: ${file}`);
            return Buffer.from(files.get(file));
        },
    };
    const shell = {
        async setSystemProxy() {
            observed.systemProxyAttempts.push('set');
            throw new Error('Android must not enable the desktop system proxy');
        },
        async closeSystemProxy() {
            observed.systemProxyAttempts.push('close');
            throw new Error('Android must not disable the desktop system proxy');
        },
    };
    const childProcess = {
        fork() {
            throw new Error('Android must not launch a child process');
        },
    };
    const server = {
        start(config) {
            // Snapshot the handoff so later mutations cannot hide ordering bugs.
            observed.serverStarts.push(structuredClone(config));
            return Object.hasOwn(options, 'serverFailure')
                ? Promise.reject(options.serverFailure)
                : Promise.resolve();
        },
    };
    const devSidecar = {
        api: {
            async startup(settings) {
                observed.startupCalls.push(structuredClone(settings));
                observed.hookResults.push(await shell.setSystemProxy());
                observed.hookResults.push(await shell.closeSystemProxy());
                if (Object.hasOwn(options, 'startupFailure')) {
                    throw options.startupFailure;
                }
                // The fake core exercises the same entrypoint seam as the real
                // core, without importing any payload or opening a socket.
                observed.child = childProcess.fork('unused-on-android', [serverConfigFile]);
            },
        },
    };
    const modules = new Map([
        ['path', androidPath],
        ['fs', fakeFs],
        ['child_process', childProcess],
        [coreModule, devSidecar],
        [androidPath.join(coreModule, 'src/shell'), shell],
        [androidPath.join(coreModule, 'package.json'), { version: 'offline-test' }],
        [proxyModule, server],
        [androidPath.join(proxyModule, 'src/json.js'), { parse: JSON.parse }],
        [kotlinModule, { marker: 'compiled kotlin module' }],
        ['./kotlin-helpers', {
            install(corePath, helpers) {
                assert.equal(corePath, core);
                assert.equal(helpers, modules.get(kotlinModule));
                observed.installations.push('kotlin');
            },
        }],
        ['./http-navigation', {
            install(corePath) {
                assert.equal(corePath, core);
                observed.installations.push('navigation');
            },
        }],
    ]);

    vm.runInNewContext(source, {
        __dirname: root,
        process: fakeProcess,
        console: {
            log: (...args) => observed.logs.push(args),
            error: (...args) => observed.errors.push(args),
        },
        require(id) {
            assert.ok(modules.has(id), `Unexpected module load: ${id}`);
            if (id === coreModule) {
                assert.deepEqual(observed.installations, ['kotlin']);
                observed.coreLoads.push({
                    env: { ...fakeProcess.env },
                    config: files.get(configFile),
                    directories: structuredClone(observed.directories),
                });
            }
            if (id === proxyModule) {
                assert.deepEqual(observed.installations, ['kotlin', 'navigation']);
            }
            return modules.get(id);
        },
    }, { filename: entrypoint, timeout: 1000 });

    // Drain async startup and its logging chains across event-loop turns.
    // Use real promises: node:test also detects leaked unhandled rejections.
    await new Promise(setImmediate);
    await new Promise(setImmediate);
    return observed;
}

function assertPort(observed, port) {
    assert.equal(JSON.parse(observed.files.get(configFile)).server.port, port);
    assert.equal(observed.serverStarts.length, 1);
    assert.equal(observed.serverStarts[0].port, port);
    assert.ok(observed.logs.some((args) => args.includes(`127.0.0.1:${port}`)),
        'Successful startup should log the listening endpoint');
}

test('default startup prepares Android paths and minimal config before loading core', async () => {
    const observed = await boot();
    assert.equal(observed.coreLoads.length, 1);
    const loaded = observed.coreLoads[0];
    assert.equal(loaded.env.HOME, home);
    assert.equal(loaded.env.USERPROFILE, home);
    assert.equal(loaded.env.DS_EXTRA_PATH, androidPath.join(core, 'extra'));
    assert.equal(loaded.env.DSHA_KOTLIN_HELPERS, kotlinModule);
    assert.deepEqual(observed.installations, ['kotlin', 'navigation']);
    assert.ok(loaded.directories.some((item) => item.directory === base && item.recursive),
        'The user directory must exist before core initialization');
    const config = JSON.parse(loaded.config);
    assert.equal(config.app.closeStrategy, 2);
    assert.equal(config.proxy.enabled, false);
    for (const plugin of ['node', 'git', 'pip', 'share', 'free_eye', 'overwall']) {
        assert.equal(config.plugin[plugin].enabled, false, `${plugin} must default to disabled`);
    }
    assert.equal(config.server.enabled, true);
    assert.deepEqual(observed.writes, [configFile]);
    assertPort(observed, 31181);
    assert.deepEqual(observed.errors, []);
});

test('DSHA_PORT controls both generated configuration and the in-process server', async () => {
    const observed = await boot({ env: { DSHA_PORT: '32123' } });
    assertPort(observed, 32123);
    assert.deepEqual(observed.errors, []);
});

test('an empty DSHA_PORT uses the default port', async () => {
    assertPort(await boot({ env: { DSHA_PORT: '' } }), 31181);
});

test('existing user configuration is preserved while the host port controls the server', async () => {
    const existingConfig = '{\n "server": {"port": 19000}, "custom": {"keep": true}\n}\n';
    const observed = await boot({
        existingConfig,
        env: { DSHA_PORT: '32124' },
    });
    assert.equal(observed.coreLoads[0].config, existingConfig);
    assert.equal(observed.files.get(configFile), existingConfig);
    assert.deepEqual(observed.writes, []);
    assert.equal(observed.serverStarts[0].port, 32124);
    assert.deepEqual(observed.errors, []);
});

test('server handoff removes null intercepts and retains valid rules and options', async () => {
    const observed = await boot();
    const config = observed.serverStarts[0];
    assert.deepEqual(config.intercepts, {
        'mixed.example': {
            '/enabled': { redirect: 'https://destination.example' },
            '/false': false,
            '/zero': 0,
            '/empty': '',
        },
        'empty.example': {},
    });
    assert.equal(config.setting.rootDir, androidPath.join(core, 'src'));
    assert.equal(config.setting.retained, 'setting');
    assert.deepEqual(config.retained, { value: 'server option' });
});

test('missing or null intercept collections do not prevent startup', async (t) => {
    for (const value of ['missing', null]) {
        await t.test(String(value), async () => {
            const config = defaultServerConfig();
            if (value === 'missing') {
                delete config.intercepts;
            } else {
                config.intercepts = value;
            }
            const observed = await boot({ serverConfig: config });
            assert.equal(observed.serverStarts.length, 1);
            assert.deepEqual(observed.errors, []);
            assertPort(observed, 31181);
        });
    }
});

test('startup uses Android storage and disables both desktop proxy hooks', async () => {
    const observed = await boot();
    assert.deepEqual(observed.startupCalls, [{
        mitmproxyPath: 'in-process',
        setting: { userBasePath: base, overwall: false },
    }]);
    assert.deepEqual(observed.hookResults, [true, true]);
    assert.deepEqual(observed.systemProxyAttempts, []);
    assert.equal(observed.child.pid, 4242);
    assert.equal(observed.child.connected, false);
});

test('rejected core startup is logged without announcing readiness', async (t) => {
    for (const failure of [new Error('offline core startup failure'), 'startup rejected']) {
        await t.test(typeof failure, async () => {
            const observed = await boot({ startupFailure: failure });
            assert.equal(observed.errors.length, 1);
            assert.match(String(observed.errors[0][0]), /startup/);
            assert.ok(observed.errors[0].includes(failure.stack ?? failure));
            assert.equal(observed.logs.some((args) => args.includes('127.0.0.1:31181')), false);
            assert.equal(observed.serverStarts.length, 0);
        });
    }
});

test('rejected in-process server startup is caught and logged', async (t) => {
    for (const failure of [new Error('offline server startup failure'), 'server rejected', null]) {
        await t.test(String(failure), async () => {
            const observed = await boot({ serverFailure: failure });
            assert.equal(observed.serverStarts.length, 1);
            assert.equal(observed.errors.length, 1);
            assert.match(String(observed.errors[0][0]), /mitmproxy/);
            assert.ok(observed.errors[0].includes(failure?.stack ?? failure));
        });
    }
});

test('uncaught-exception logging stays isolated from the real Node process', async () => {
    const observed = await boot();
    const handlers = observed.handlers.get('uncaughtException') ?? [];
    assert.ok(handlers.length > 0);
    const failure = new Error('offline uncaught exception');
    for (const handler of handlers) {
        handler(failure);
    }
    assert.ok(observed.errors.some((args) => args.includes(failure.stack)));
});
