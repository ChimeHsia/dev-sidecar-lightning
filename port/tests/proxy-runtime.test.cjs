const assert = require('node:assert/strict');
const http = require('node:http');
const { once } = require('node:events');
const { test } = require('node:test');
const { startProxy } = require('../node/proxy-runtime');

function fixture(t, create) {
    let servers = [];
    const proxy = {
        createProxy(config) {
            servers = create(config);
            for (const server of servers) server.on('error', () => {});
            return servers;
        },
    };
    const original = proxy.createProxy;
    const api = {
        async start(config) { proxy.createProxy(config); },
        async close() {
            for (const server of servers) {
                if (server.listening) {
                    await new Promise((resolve) => server.close(resolve));
                }
            }
        },
    };
    t.after(() => api.close());
    return { proxy, api, original };
}

test('readiness waits for the actual delayed listener, not async start return', async (t) => {
    const server = http.createServer();
    const f = fixture(t, () => [server]);
    let ready = false;
    const starting = startProxy(f.api, f.proxy, {}, { timeoutMillis: 1000 })
        .then((servers) => { ready = true; return servers; });
    await new Promise(setImmediate);
    assert.equal(ready, false);
    server.listen(0, '127.0.0.1');
    assert.deepEqual(await starting, [server]);
    assert.equal(server.listening, true);
    assert.equal(f.proxy.createProxy, f.original);
});

test('a real occupied loopback port rejects readiness with EADDRINUSE', async (t) => {
    const occupied = http.createServer().listen(0, '127.0.0.1');
    await once(occupied, 'listening');
    t.after(() => new Promise((resolve) => occupied.close(resolve)));
    const f = fixture(t, () => [http.createServer().listen(occupied.address().port, '127.0.0.1')]);
    await assert.rejects(startProxy(f.api, f.proxy, {}), { code: 'EADDRINUSE' });
    assert.equal(f.proxy.createProxy, f.original);
    assert.equal(occupied.listening, true, 'A competitor must not be closed');
});

test('missing listeners and a listener that never binds fail explicitly', async (t) => {
    const empty = fixture(t, () => []);
    await assert.rejects(startProxy(empty.api, empty.proxy, {}), /no proxy listeners/);
    const never = fixture(t, () => [http.createServer()]);
    await assert.rejects(startProxy(never.api, never.proxy, {}, { timeoutMillis: 10 }), /timed out/);
});

test('initialization rejecting after bind closes the listener and preserves the original error', async (t) => {
    const server = http.createServer();
    const f = fixture(t, () => [server]);
    const error = new Error('initialization failed');
    f.api.start = async () => {
        f.proxy.createProxy({});
        server.listen(0, '127.0.0.1');
        await once(server, 'listening');
        throw error;
    };
    await assert.rejects(startProxy(f.api, f.proxy, {}), (actual) => actual === error);
    assert.equal(server.listening, false);
});
